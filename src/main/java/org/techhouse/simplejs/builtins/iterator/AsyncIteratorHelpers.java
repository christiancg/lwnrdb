package org.techhouse.simplejs.builtins.iterator;

import static org.techhouse.simplejs.builtins.AsyncIteratorBuiltins.asyncIterator;
import static org.techhouse.simplejs.builtins.AsyncIteratorBuiltins.getAsyncIterator;
import static org.techhouse.simplejs.builtins.AsyncIteratorBuiltins.guarded;
import static org.techhouse.simplejs.builtins.AsyncIteratorBuiltins.step;
import static org.techhouse.simplejs.builtins.AsyncIteratorBuiltins.toPromise;

import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class AsyncIteratorHelpers {
    public static JsValue map(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var index = new long[]{0};
        return asyncIterator(() -> {
            final var out = new JsPromise(loop);
            source.step().subscribe(result -> {
                if (source.isDone(result)) {
                    out.resolve(result);
                    return;
                }
                guarded(out, () -> {
                    final var mapped = ops.call(fn, JsUndefined.getInstance(),
                            List.of(source.valueOf(result), new JsNumber(index[0]++)));
                    toPromise(loop, mapped).subscribe(value -> out.resolve(step(value, false)), out::reject);
                });
            }, out::reject);
            return out;
        });
    }

    public static JsValue filter(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var index = new long[]{0};
        return asyncIterator(() -> {
            final var out = new JsPromise(loop);
            filterStep(ops, loop, source, fn, index, out);
            return out;
        });
    }

    public static void filterStep(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn, long[] index,
            JsPromise out) {
        source.step().subscribe(result -> {
            if (source.isDone(result)) {
                out.resolve(result);
                return;
            }
            final var value = source.valueOf(result);
            guarded(out, () -> {
                final var kept = ops.call(fn, JsUndefined.getInstance(), List.of(value, new JsNumber(index[0]++)));
                toPromise(loop, kept).subscribe(flag -> {
                    if (JsCoercion.toBoolean(flag)) {
                        out.resolve(step(value, false));
                    } else {
                        filterStep(ops, loop, source, fn, index, out);
                    }
                }, out::reject);
            });
        }, out::reject);
    }

    public static JsValue take(EventLoop loop, AsyncDriver source, long count) {
        final var remaining = new long[]{count};
        return asyncIterator(() -> {
            final var out = new JsPromise(loop);
            if (remaining[0] <= 0) {
                source.close();
                out.resolve(step(JsUndefined.getInstance(), true));
                return out;
            }
            remaining[0]--;
            source.step().subscribe(out::resolve, out::reject);
            return out;
        });
    }

    public static JsValue drop(EventLoop loop, AsyncDriver source, long count) {
        final var remaining = new long[]{count};
        return asyncIterator(() -> {
            final var out = new JsPromise(loop);
            dropStep(source, remaining, out);
            return out;
        });
    }

    public static void dropStep(AsyncDriver source, long[] remaining, JsPromise out) {
        source.step().subscribe(result -> {
            if (source.isDone(result) || remaining[0] <= 0) {
                out.resolve(result);
                return;
            }
            remaining[0]--;
            dropStep(source, remaining, out);
        }, out::reject);
    }

    public static JsValue flatMap(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var index = new long[]{0};
        final var inner = new AsyncDriver[]{null};
        return asyncIterator(() -> {
            final var out = new JsPromise(loop);
            flatMapStep(ops, loop, source, fn, index, inner, out);
            return out;
        });
    }

    public static void flatMapStep(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn, long[] index,
            AsyncDriver[] inner, JsPromise out) {
        if (inner[0] != null) {
            inner[0].step().subscribe(result -> {
                if (inner[0].isDone(result)) {
                    inner[0] = null;
                    flatMapStep(ops, loop, source, fn, index, inner, out);
                } else {
                    out.resolve(step(inner[0].valueOf(result), false));
                }
            }, out::reject);
            return;
        }
        source.step().subscribe(result -> {
            if (source.isDone(result)) {
                out.resolve(result);
                return;
            }
            guarded(out, () -> {
                final var mapped = ops.call(fn, JsUndefined.getInstance(),
                        List.of(source.valueOf(result), new JsNumber(index[0]++)));
                toPromise(loop, mapped).subscribe(value -> {
                    inner[0] = new AsyncDriver(ops, loop, getAsyncIterator(ops, loop, value));
                    flatMapStep(ops, loop, source, fn, index, inner, out);
                }, out::reject);
            });
        }, out::reject);
    }

    public static JsValue reduce(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn,
            List<JsValue> args) {
        final var out = new JsPromise(loop);
        final var accumulator = new JsValue[]{args.size() > 1 ? args.get(1) : null};
        final var index = new long[]{0};
        reduceStep(ops, loop, source, fn, accumulator, index, out, args.size() > 1);
        return out;
    }

    public static void reduceStep(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn,
            JsValue[] accumulator, long[] index, JsPromise out, boolean seeded) {
        source.step().subscribe(result -> {
            if (source.isDone(result)) {
                if (accumulator[0] == null) {
                    out.reject(InterpreterUtils.toErrorValue(
                            new TypeErrorException("Reduce of empty iterator with no initial value"),
                            loop.intrinsics()));
                } else {
                    out.resolve(accumulator[0]);
                }
                return;
            }
            final var value = source.valueOf(result);
            if (accumulator[0] == null && !seeded) {
                accumulator[0] = value;
                reduceStep(ops, loop, source, fn, accumulator, index, out, true);
                return;
            }
            guarded(out, () -> {
                final var next = ops.call(fn, JsUndefined.getInstance(),
                        List.of(accumulator[0], value, new JsNumber(index[0]++)));
                toPromise(loop, next).subscribe(reduced -> {
                    accumulator[0] = reduced;
                    reduceStep(ops, loop, source, fn, accumulator, index, out, true);
                }, out::reject);
            });
        }, out::reject);
    }

    public static JsValue toArray(EventLoop loop, AsyncDriver source) {
        final var out = new JsPromise(loop);
        final var array = new JsArray();
        toArrayStep(source, array, out);
        return out;
    }

    public static void toArrayStep(AsyncDriver source, JsArray array, JsPromise out) {
        source.step().subscribe(result -> {
            if (source.isDone(result)) {
                out.resolve(array);
                return;
            }
            array.push(source.valueOf(result));
            toArrayStep(source, array, out);
        }, error -> {
            source.close();
            out.reject(error);
        });
    }

    public static JsValue forEach(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var out = new JsPromise(loop);
        final var index = new long[]{0};
        forEachStep(ops, loop, source, fn, index, out);
        return out;
    }

    public static void forEachStep(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn, long[] index,
            JsPromise out) {
        source.step().subscribe(result -> {
            if (source.isDone(result)) {
                out.resolve(JsUndefined.getInstance());
                return;
            }
            guarded(out, () -> {
                final var callResult = ops.call(fn, JsUndefined.getInstance(),
                        List.of(source.valueOf(result), new JsNumber(index[0]++)));
                toPromise(loop, callResult).subscribe(_ -> forEachStep(ops, loop, source, fn, index, out), out::reject);
            });
        }, out::reject);
    }

    public static JsValue matchAny(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var out = new JsPromise(loop);
        predicateSearch(ops, loop, source, fn, new long[]{0}, out, true, false);
        return out;
    }

    public static JsValue matchAll(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var out = new JsPromise(loop);
        predicateSearch(ops, loop, source, fn, new long[]{0}, out, false, true);
        return out;
    }

    public static void predicateSearch(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn, long[] index,
            JsPromise out, boolean matchWins, boolean allDefault) {
        source.step().subscribe(result -> {
            if (source.isDone(result)) {
                out.resolve(JsBoolean.of(allDefault));
                return;
            }
            guarded(out, () -> {
                final var flag = ops.call(fn, JsUndefined.getInstance(),
                        List.of(source.valueOf(result), new JsNumber(index[0]++)));
                toPromise(loop, flag).subscribe(value -> {
                    if (JsCoercion.toBoolean(value) == matchWins) {
                        source.close();
                        out.resolve(JsBoolean.of(matchWins));
                    } else {
                        predicateSearch(ops, loop, source, fn, index, out, matchWins, allDefault);
                    }
                }, out::reject);
            });
        }, out::reject);
    }

    public static JsValue find(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var out = new JsPromise(loop);
        findStep(ops, loop, source, fn, new long[]{0}, out);
        return out;
    }

    public static void findStep(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn, long[] index,
            JsPromise out) {
        source.step().subscribe(result -> {
            if (source.isDone(result)) {
                out.resolve(JsUndefined.getInstance());
                return;
            }
            final var value = source.valueOf(result);
            guarded(out, () -> {
                final var flag = ops.call(fn, JsUndefined.getInstance(), List.of(value, new JsNumber(index[0]++)));
                toPromise(loop, flag).subscribe(matched -> {
                    if (JsCoercion.toBoolean(matched)) {
                        source.close();
                        out.resolve(value);
                    } else {
                        findStep(ops, loop, source, fn, index, out);
                    }
                }, out::reject);
            });
        }, out::reject);
    }

    private AsyncIteratorHelpers() {
    }
}

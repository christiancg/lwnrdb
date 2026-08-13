package org.techhouse.simplejs.builtins;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * ES2025 async iterator helpers. The {@code AsyncIterator} global exposes {@code AsyncIterator.from}
 * plus a {@code prototype} carrying the helpers; the interpreter routes helper names on async
 * generators and on async-iterator-like objects (an own callable {@code next} plus a
 * {@code Symbol.asyncIterator}) to {@link #helper}. Each helper drives its receiver through the
 * {@link InterpreterOps} seam, treating {@code next()} results as promises and awaiting them (and any
 * promise-returning callback result) via the {@link EventLoop}.
 */
public final class AsyncIteratorBuiltins {
    private static final Set<String> HELPERS = Set.of("map", "filter", "take", "drop", "flatMap", "reduce", "toArray",
            "forEach", "some", "every", "find");

    private AsyncIteratorBuiltins() {
    }

    public static boolean isHelperName(String name) {
        return HELPERS.contains(name);
    }

    public static JsNativeFunction create(InterpreterOps ops, EventLoop loop) {
        final var ctor = new JsNativeFunction("AsyncIterator", (_, _) -> {
            throw new TypeErrorException("Abstract class AsyncIterator not directly constructable");
        });
        final var prototype = new JsObject();
        for (final var name : HELPERS) {
            prototype.set(name, helper(ops, loop, name));
        }
        prototype.setSymbol(JsSymbol.ASYNC_ITERATOR,
                new JsNativeFunction("[Symbol.asyncIterator]", (thisArg, _) -> thisArg));
        prototype.defineValue("constructor", ctor);
        prototype.setFlags("constructor", new JsObject.PropertyFlags(true, false, true));
        ctor.setProperty("prototype", prototype);
        ctor.setProperty("from", new JsNativeFunction("from", (_, args) -> getAsyncIterator(ops, loop, arg0(args))));
        // The dedicated field (not just the "prototype" own property) is what `instanceof`/`new`
        // consult for a JsNativeFunction - GlobalScope must not overwrite this with the unrelated
        // AsyncGenerator.prototype intrinsic.
        ctor.setPrototype(prototype);
        return ctor;
    }

    public static JsValue drainToArray(InterpreterOps ops, EventLoop loop, JsValue iterable) {
        return toArray(loop, new AsyncDriver(ops, loop, getAsyncIterator(ops, loop, iterable)));
    }

    public static JsNativeFunction helper(InterpreterOps ops, EventLoop loop, String name) {
        return new JsNativeFunction(name, (thisArg, args) -> dispatch(ops, loop, name, thisArg, args));
    }

    private static JsValue dispatch(InterpreterOps ops, EventLoop loop, String name, JsValue thisArg,
            List<JsValue> args) {
        final var source = new AsyncDriver(ops, loop, requireIterator(thisArg));
        return switch (name) {
            case "map" -> map(ops, loop, source, callback(args));
            case "filter" -> filter(ops, loop, source, callback(args));
            case "take" -> take(loop, source, limit(arg0(args)));
            case "drop" -> drop(loop, source, limit(arg0(args)));
            case "flatMap" -> flatMap(ops, loop, source, callback(args));
            case "reduce" -> reduce(ops, loop, source, callback(args), args);
            case "toArray" -> toArray(loop, source);
            case "forEach" -> forEach(ops, loop, source, callback(args));
            case "some" -> matchAny(ops, loop, source, callback(args));
            case "every" -> matchAll(ops, loop, source, callback(args));
            case "find" -> find(ops, loop, source, callback(args));
            default -> JsUndefined.getInstance();
        };
    }

    private static JsValue map(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
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

    private static JsValue filter(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var index = new long[]{0};
        return asyncIterator(() -> {
            final var out = new JsPromise(loop);
            filterStep(ops, loop, source, fn, index, out);
            return out;
        });
    }

    private static void filterStep(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn, long[] index,
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

    private static JsValue take(EventLoop loop, AsyncDriver source, long count) {
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

    private static JsValue drop(EventLoop loop, AsyncDriver source, long count) {
        final var remaining = new long[]{count};
        return asyncIterator(() -> {
            final var out = new JsPromise(loop);
            dropStep(source, remaining, out);
            return out;
        });
    }

    private static void dropStep(AsyncDriver source, long[] remaining, JsPromise out) {
        source.step().subscribe(result -> {
            if (source.isDone(result) || remaining[0] <= 0) {
                out.resolve(result);
                return;
            }
            remaining[0]--;
            dropStep(source, remaining, out);
        }, out::reject);
    }

    private static JsValue flatMap(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var index = new long[]{0};
        final var inner = new AsyncDriver[]{null};
        return asyncIterator(() -> {
            final var out = new JsPromise(loop);
            flatMapStep(ops, loop, source, fn, index, inner, out);
            return out;
        });
    }

    private static void flatMapStep(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn, long[] index,
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

    private static JsValue reduce(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn,
            List<JsValue> args) {
        final var out = new JsPromise(loop);
        final var accumulator = new JsValue[]{args.size() > 1 ? args.get(1) : null};
        final var index = new long[]{0};
        reduceStep(ops, loop, source, fn, accumulator, index, out, args.size() > 1);
        return out;
    }

    private static void reduceStep(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn,
            JsValue[] accumulator, long[] index, JsPromise out, boolean seeded) {
        source.step().subscribe(result -> {
            if (source.isDone(result)) {
                if (accumulator[0] == null) {
                    out.reject(InterpreterUtils
                            .toErrorValue(new TypeErrorException("Reduce of empty iterator with no initial value")));
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

    private static JsValue toArray(EventLoop loop, AsyncDriver source) {
        final var out = new JsPromise(loop);
        final var array = new JsArray();
        toArrayStep(source, array, out);
        return out;
    }

    private static void toArrayStep(AsyncDriver source, JsArray array, JsPromise out) {
        source.step().subscribe(result -> {
            if (source.isDone(result)) {
                out.resolve(array);
                return;
            }
            array.push(source.valueOf(result));
            toArrayStep(source, array, out);
        }, out::reject);
    }

    private static JsValue forEach(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var out = new JsPromise(loop);
        final var index = new long[]{0};
        forEachStep(ops, loop, source, fn, index, out);
        return out;
    }

    private static void forEachStep(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn, long[] index,
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

    private static JsValue matchAny(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var out = new JsPromise(loop);
        predicateSearch(ops, loop, source, fn, new long[]{0}, out, true, false);
        return out;
    }

    private static JsValue matchAll(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var out = new JsPromise(loop);
        predicateSearch(ops, loop, source, fn, new long[]{0}, out, false, true);
        return out;
    }

    private static void predicateSearch(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn,
            long[] index, JsPromise out, boolean matchWins, boolean allDefault) {
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

    private static JsValue find(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn) {
        final var out = new JsPromise(loop);
        findStep(ops, loop, source, fn, new long[]{0}, out);
        return out;
    }

    private static void findStep(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue fn, long[] index,
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

    private static JsObject asyncIterator(Supplier<JsPromise> nextStep) {
        final var iterator = new JsObject();
        iterator.set("next", new JsNativeFunction("next", (_, _) -> nextStep.get()));
        iterator.setSymbol(JsSymbol.ASYNC_ITERATOR, new JsNativeFunction("[Symbol.asyncIterator]", (_, _) -> iterator));
        return iterator;
    }

    private static JsValue getAsyncIterator(InterpreterOps ops, EventLoop loop, JsValue value) {
        final var asyncIterFn = ops.getMember(value, JsSymbol.ASYNC_ITERATOR);
        if (isCallable(asyncIterFn)) {
            return ops.call(asyncIterFn, value, List.of());
        }
        final var syncIterFn = ops.getMember(value, JsSymbol.ITERATOR);
        if (isCallable(syncIterFn)) {
            return syncToAsync(ops, loop, ops.call(syncIterFn, value, List.of()));
        }
        return requireIterator(value);
    }

    private static JsValue syncToAsync(InterpreterOps ops, EventLoop loop, JsValue syncIterator) {
        return asyncIterator(() -> {
            final var out = new JsPromise(loop);
            final var nextFn = ops.getMember(syncIterator, new JsString("next"));
            if (!isCallable(nextFn)) {
                out.reject(InterpreterUtils.toErrorValue(new TypeErrorException("iterator.next is not a function")));
                return out;
            }
            guarded(out, () -> {
                final var result = ops.call(nextFn, syncIterator, List.of());
                if (JsCoercion.toBoolean(ops.getMember(result, new JsString("done")))) {
                    out.resolve(step(JsUndefined.getInstance(), true));
                } else {
                    toPromise(loop, ops.getMember(result, new JsString("value")))
                            .subscribe(value -> out.resolve(step(value, false)), out::reject);
                }
            });
            return out;
        });
    }

    private static JsPromise toPromise(EventLoop loop, JsValue value) {
        final var promise = new JsPromise(loop);
        promise.resolve(value);
        return promise;
    }

    private static JsObject step(JsValue value, boolean done) {
        final var result = new JsObject();
        result.set("value", value == null ? JsUndefined.getInstance() : value);
        result.set("done", JsBoolean.of(done));
        return result;
    }

    private static void guarded(JsPromise out, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException error) {
            out.reject(InterpreterUtils.toErrorValue(error));
        }
    }

    private static JsValue requireIterator(JsValue value) {
        if (value instanceof JsUndefined || value == null) {
            throw new TypeErrorException("Async iterator helper called on non-iterator");
        }
        return value;
    }

    private static JsValue callback(List<JsValue> args) {
        final var fn = arg0(args);
        if (!isCallable(fn)) {
            throw new TypeErrorException("Async iterator helper callback is not a function");
        }
        return fn;
    }

    private static long limit(JsValue value) {
        final var number = JsCoercion.toNumber(value);
        if (Double.isNaN(number) || number < 0) {
            throw new RangeErrorException("Async iterator helper limit must be a non-negative number");
        }
        return (long) number;
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    private static JsValue arg0(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }

    private static final class AsyncDriver {
        private final InterpreterOps ops;
        private final EventLoop loop;
        private final JsValue iterator;
        private boolean done;

        private AsyncDriver(InterpreterOps ops, EventLoop loop, JsValue iterator) {
            this.ops = ops;
            this.loop = loop;
            this.iterator = iterator;
        }

        private JsPromise step() {
            final var out = new JsPromise(loop);
            if (done) {
                out.resolve(AsyncIteratorBuiltins.step(JsUndefined.getInstance(), true));
                return out;
            }
            final var nextFn = ops.getMember(iterator, new JsString("next"));
            if (!isCallable(nextFn)) {
                out.reject(InterpreterUtils.toErrorValue(new TypeErrorException("iterator.next is not a function")));
                return out;
            }
            guarded(out, () -> {
                final var result = ops.call(nextFn, iterator, List.of());
                toPromise(loop, result).subscribe(settled -> {
                    if (JsCoercion.toBoolean(ops.getMember(settled, new JsString("done")))) {
                        done = true;
                    }
                    out.resolve(settled);
                }, out::reject);
            });
            return out;
        }

        private boolean isDone(JsValue result) {
            return JsCoercion.toBoolean(ops.getMember(result, new JsString("done")));
        }

        private JsValue valueOf(JsValue result) {
            return ops.getMember(result, new JsString("value"));
        }

        private void close() {
            if (done) {
                return;
            }
            done = true;
            final var returnFn = ops.getMember(iterator, new JsString("return"));
            if (isCallable(returnFn)) {
                ops.call(returnFn, iterator, List.of());
            }
        }
    }
}

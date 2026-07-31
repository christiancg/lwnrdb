package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class PromiseBuiltins {
    private PromiseBuiltins() {
    }

    public static JsNativeFunction create(EventLoop eventLoop, Invoker invoker, IterableToList iterableToList) {
        final var promise = new JsNativeFunction("Promise", (_, args) -> construct(eventLoop, invoker, args));
        promise.setProperty("resolve", new JsNativeFunction("resolve", (_, args) -> resolved(eventLoop, arg0(args))));
        promise.setProperty("reject", new JsNativeFunction("reject", (_, args) -> rejected(eventLoop, arg0(args))));
        promise.setProperty("all",
                new JsNativeFunction("all", (_, args) -> all(eventLoop, arg0(args), iterableToList)));
        promise.setProperty("race",
                new JsNativeFunction("race", (_, args) -> race(eventLoop, arg0(args), iterableToList)));
        promise.setProperty("allSettled",
                new JsNativeFunction("allSettled", (_, args) -> allSettled(eventLoop, arg0(args), iterableToList)));
        promise.setProperty("any",
                new JsNativeFunction("any", (_, args) -> any(eventLoop, arg0(args), iterableToList)));
        promise.setProperty("withResolvers", new JsNativeFunction("withResolvers", (_, _) -> withResolvers(eventLoop)));
        promise.setProperty("try", new JsNativeFunction("try", (_, args) -> tryCall(eventLoop, invoker, args)));
        return promise;
    }

    private static JsValue withResolvers(EventLoop eventLoop) {
        final var promise = new JsPromise(eventLoop);
        final var resolve = new JsNativeFunction("resolve", (_, a) -> {
            promise.resolve(arg0(a));
            return JsUndefined.getInstance();
        });
        final var reject = new JsNativeFunction("reject", (_, a) -> {
            promise.reject(arg0(a));
            return JsUndefined.getInstance();
        });
        final var result = new JsObject();
        result.set("promise", promise);
        result.set("resolve", resolve);
        result.set("reject", reject);
        return result;
    }

    private static JsValue tryCall(EventLoop eventLoop, Invoker invoker, List<JsValue> args) {
        final var callback = arg0(args);
        final var rest = args.isEmpty() ? List.<JsValue>of() : args.subList(1, args.size());
        final var promise = new JsPromise(eventLoop);
        try {
            resolved(eventLoop, invoker.call(callback, JsUndefined.getInstance(), rest)).subscribe(promise::resolve,
                    promise::reject);
        } catch (JsThrowException error) {
            promise.reject(error.getValue());
        }
        return promise;
    }

    private static JsValue construct(EventLoop eventLoop, Invoker invoker, List<JsValue> args) {
        final var executor = arg0(args);
        if (!(executor instanceof JsFunction) && !(executor instanceof JsNativeFunction)) {
            throw new TypeErrorException("Promise resolver is not a function");
        }
        final var promise = new JsPromise(eventLoop);
        final var resolve = new JsNativeFunction("resolve", (_, a) -> {
            promise.resolve(arg0(a));
            return JsUndefined.getInstance();
        });
        final var reject = new JsNativeFunction("reject", (_, a) -> {
            promise.reject(arg0(a));
            return JsUndefined.getInstance();
        });
        try {
            invoker.call(executor, JsUndefined.getInstance(), List.of(resolve, reject));
        } catch (JsThrowException error) {
            promise.reject(error.getValue());
        }
        return promise;
    }

    private static JsPromise resolved(EventLoop eventLoop, JsValue value) {
        if (value instanceof JsPromise promise) {
            return promise;
        }
        final var promise = new JsPromise(eventLoop);
        promise.resolve(value);
        return promise;
    }

    private static JsPromise rejected(EventLoop eventLoop, JsValue reason) {
        final var promise = new JsPromise(eventLoop);
        promise.reject(reason);
        return promise;
    }

    private static JsValue all(EventLoop eventLoop, JsValue iterable, IterableToList iterableToList) {
        final var elements = elements(iterable, iterableToList);
        final var derived = new JsPromise(eventLoop);
        final var results = new JsArray();
        final var remaining = new int[]{elements.size()};
        if (elements.isEmpty()) {
            derived.resolve(results);
            return derived;
        }
        for (var i = 0; i < elements.size(); i++) {
            results.push(JsUndefined.getInstance());
            final var index = i;
            resolved(eventLoop, elements.get(i)).subscribe(value -> {
                results.set(index, value);
                remaining[0]--;
                if (remaining[0] == 0) {
                    derived.resolve(results);
                }
            }, derived::reject);
        }
        return derived;
    }

    private static JsValue race(EventLoop eventLoop, JsValue iterable, IterableToList iterableToList) {
        final var derived = new JsPromise(eventLoop);
        for (final var element : elements(iterable, iterableToList)) {
            resolved(eventLoop, element).subscribe(derived::resolve, derived::reject);
        }
        return derived;
    }

    private static JsValue allSettled(EventLoop eventLoop, JsValue iterable, IterableToList iterableToList) {
        final var elements = elements(iterable, iterableToList);
        final var derived = new JsPromise(eventLoop);
        final var results = new JsArray();
        final var remaining = new int[]{elements.size()};
        if (elements.isEmpty()) {
            derived.resolve(results);
            return derived;
        }
        for (var i = 0; i < elements.size(); i++) {
            results.push(JsUndefined.getInstance());
            final var index = i;
            resolved(eventLoop, elements.get(i)).subscribe(value -> {
                results.set(index, outcome("fulfilled", "value", value));
                if (--remaining[0] == 0) {
                    derived.resolve(results);
                }
            }, reason -> {
                results.set(index, outcome("rejected", "reason", reason));
                if (--remaining[0] == 0) {
                    derived.resolve(results);
                }
            });
        }
        return derived;
    }

    private static JsValue any(EventLoop eventLoop, JsValue iterable, IterableToList iterableToList) {
        final var elements = elements(iterable, iterableToList);
        final var derived = new JsPromise(eventLoop);
        if (elements.isEmpty()) {
            derived.reject(ErrorBuiltins.makeAggregateError(List.of(), "All promises were rejected"));
            return derived;
        }
        final var reasons = new JsValue[elements.size()];
        final var remaining = new int[]{elements.size()};
        for (var i = 0; i < elements.size(); i++) {
            final var index = i;
            resolved(eventLoop, elements.get(i)).subscribe(derived::resolve, reason -> {
                reasons[index] = reason;
                if (--remaining[0] == 0) {
                    derived.reject(ErrorBuiltins.makeAggregateError(List.of(reasons), "All promises were rejected"));
                }
            });
        }
        return derived;
    }

    private static JsObject outcome(String status, String field, JsValue value) {
        final var entry = new JsObject();
        entry.set("status", new JsString(status));
        entry.set(field, value);
        return entry;
    }

    private static List<JsValue> elements(JsValue iterable, IterableToList iterableToList) {
        if (iterable instanceof JsArray array) {
            return array.getElements();
        }
        return iterableToList.drain(iterable);
    }

    private static JsValue arg0(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }
}

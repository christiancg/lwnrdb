package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class PromiseBuiltins {
    private PromiseBuiltins() {
    }

    public static JsNativeFunction create(EventLoop eventLoop, Invoker invoker) {
        final var promise = new JsNativeFunction("Promise", (_, args) -> construct(eventLoop, invoker, args));
        promise.setProperty("resolve", new JsNativeFunction("resolve", (_, args) -> resolved(eventLoop, arg0(args))));
        promise.setProperty("reject", new JsNativeFunction("reject", (_, args) -> rejected(eventLoop, arg0(args))));
        promise.setProperty("all", new JsNativeFunction("all", (_, args) -> all(eventLoop, arg0(args))));
        promise.setProperty("race", new JsNativeFunction("race", (_, args) -> race(eventLoop, arg0(args))));
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

    private static JsValue all(EventLoop eventLoop, JsValue iterable) {
        final var elements = elements(iterable);
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

    private static JsValue race(EventLoop eventLoop, JsValue iterable) {
        final var derived = new JsPromise(eventLoop);
        for (final var element : elements(iterable)) {
            resolved(eventLoop, element).subscribe(derived::resolve, derived::reject);
        }
        return derived;
    }

    private static List<JsValue> elements(JsValue iterable) {
        if (iterable instanceof JsArray array) {
            return array.getElements();
        }
        throw new TypeErrorException("Argument is not iterable");
    }

    private static JsValue arg0(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }
}

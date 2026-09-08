package org.techhouse.simplejs.builtins.iterator;

import static org.techhouse.simplejs.builtins.AsyncIteratorBuiltins.guarded;
import static org.techhouse.simplejs.builtins.AsyncIteratorBuiltins.toPromise;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;

import java.util.List;
import org.techhouse.simplejs.builtins.AsyncIteratorBuiltins;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class AsyncDriver {
    public final InterpreterOps ops;
    public final EventLoop loop;
    public final JsValue iterator;
    public boolean done;

    public AsyncDriver(InterpreterOps ops, EventLoop loop, JsValue iterator) {
        this.ops = ops;
        this.loop = loop;
        this.iterator = iterator;
    }

    public JsPromise step() {
        final var out = new JsPromise(loop);
        if (done) {
            out.resolve(AsyncIteratorBuiltins.step(JsUndefined.getInstance(), true));
            return out;
        }
        final var nextFn = ops.getMember(iterator, new JsString("next"));
        if (!isCallable(nextFn)) {
            out.reject(InterpreterUtils.toErrorValue(new TypeErrorException("iterator.next is not a function"),
                    out.intrinsics()));
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

    public boolean isDone(JsValue result) {
        return JsCoercion.toBoolean(ops.getMember(result, new JsString("done")));
    }

    public JsValue valueOf(JsValue result) {
        return ops.getMember(result, new JsString("value"));
    }

    public void close() {
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

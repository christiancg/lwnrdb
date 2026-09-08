package org.techhouse.simplejs.builtins.promise;

import static org.techhouse.simplejs.builtins.PromiseBuiltins.Ctx;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.List;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsValue;

public final class IteratorRecord {
    public final JsValue iterator;
    public final JsValue nextMethod;
    public boolean done;

    private IteratorRecord(JsValue iterator, JsValue nextMethod) {
        this.iterator = iterator;
        this.nextMethod = nextMethod;
    }

    public static IteratorRecord open(Ctx ctx, JsValue source) {
        final var method = ctx.ops().getMember(source, JsSymbol.ITERATOR);
        if (!isCallable(method)) {
            throw new TypeErrorException(JsCoercion.toStr(source) + " is not iterable");
        }
        final var iterator = ctx.invoker().call(method, source, List.of());
        if (!isObjectLike(iterator)) {
            throw new TypeErrorException("Result of Symbol.iterator method is not an object");
        }
        return new IteratorRecord(iterator, ctx.ops().getMember(iterator, new JsString("next")));
    }

    public JsValue stepValue(Ctx ctx) {
        if (done) {
            return null;
        }
        final JsValue result;
        try {
            result = ctx.invoker().call(nextMethod, iterator, List.of());
        } catch (SimpleJsRuntimeException error) {
            done = true;
            throw error;
        }
        if (!isObjectLike(result)) {
            done = true;
            throw new TypeErrorException("Iterator result is not an object");
        }
        try {
            if (JsCoercion.toBoolean(ctx.ops().getMember(result, new JsString("done")))) {
                done = true;
                return null;
            }
            return ctx.ops().getMember(result, new JsString("value"));
        } catch (SimpleJsRuntimeException error) {
            done = true;
            throw error;
        }
    }

    public SimpleJsRuntimeException closeOnAbrupt(Ctx ctx, SimpleJsRuntimeException error) {
        if (done) {
            return error;
        }
        done = true;
        try {
            final var returnFn = ctx.ops().getMember(iterator, new JsString("return"));
            if (isCallable(returnFn)) {
                ctx.invoker().call(returnFn, iterator, List.of());
            }
        } catch (SimpleJsRuntimeException ignored) {
        }
        return error;
    }
}

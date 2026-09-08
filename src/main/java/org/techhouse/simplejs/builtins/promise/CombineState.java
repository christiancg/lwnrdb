package org.techhouse.simplejs.builtins.promise;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.PromiseBuiltins.Capability;
import static org.techhouse.simplejs.builtins.PromiseBuiltins.Ctx;
import static org.techhouse.simplejs.builtins.PromiseBuiltins.call;
import static org.techhouse.simplejs.builtins.PromiseBuiltins.invoke;
import static org.techhouse.simplejs.builtins.promise.PromiseCombinators.outcome;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class CombineState {
    public final Ctx ctx;
    public final Capability capability;
    public final Variant variant;
    public final List<JsValue> values = new ArrayList<>();
    public final List<JsValue> keys = new ArrayList<>();
    public final int[] remaining = new int[]{1};
    public final boolean keyed;
    public int index;

    CombineState(Ctx ctx, Capability capability, Variant variant, boolean keyed) {
        this.ctx = ctx;
        this.capability = capability;
        this.variant = variant;
        this.keyed = keyed;
    }

    public void addSlot(JsValue key) {
        values.add(JsUndefined.getInstance());
        if (key != null) {
            keys.add(key);
        }
    }

    public void subscribe(JsValue nextPromise) {
        final var slot = index++;
        remaining[0]++;
        final var handlers = handlersFor(slot);
        invoke(ctx, nextPromise, handlers);
    }

    public List<JsValue> handlersFor(int slot) {
        final var alreadyCalled = new boolean[]{false};
        return switch (variant) {
            case ALL -> List.of(elementFunction(alreadyCalled, slot, value -> value), capability.reject());
            case ALL_SETTLED ->
                List.of(elementFunction(alreadyCalled, slot, value -> outcome(ctx, "fulfilled", "value", value)),
                        elementFunction(alreadyCalled, slot, reason -> outcome(ctx, "rejected", "reason", reason)));
            case ANY -> List.of(capability.resolve(), elementFunction(alreadyCalled, slot, reason -> reason));
        };
    }

    public JsNativeFunction elementFunction(boolean[] alreadyCalled, int slot, UnaryOperator<JsValue> mapper) {
        final var fn = new JsNativeFunction("", (_, args) -> {
            if (alreadyCalled[0]) {
                return JsUndefined.getInstance();
            }
            alreadyCalled[0] = true;
            values.set(slot, mapper.apply(arg(args, 0)));
            remaining[0]--;
            if (remaining[0] == 0) {
                settle();
            }
            return JsUndefined.getInstance();
        });
        fn.setLength(1);
        return fn;
    }

    public void finish() {
        remaining[0]--;
        if (remaining[0] == 0) {
            settle();
        }
    }

    public void settle() {
        if (variant == Variant.ANY) {
            final var aggregate = ctx.intrinsics().makeError("AggregateError", "All promises were rejected");
            aggregate.set("errors", new JsArray(new ArrayList<>(values)));
            call(ctx, capability.reject(), aggregate);
            return;
        }
        call(ctx, capability.resolve(), keyed ? keyedResult() : new JsArray(new ArrayList<>(values)));
    }

    public JsValue keyedResult() {
        final var result = new JsObject();
        for (var i = 0; i < keys.size(); i++) {
            final var key = keys.get(i);
            if (key instanceof JsSymbol symbol) {
                result.setSymbol(symbol, values.get(i));
            } else {
                result.set(JsCoercion.toStr(key), values.get(i));
            }
        }
        return result;
    }
}

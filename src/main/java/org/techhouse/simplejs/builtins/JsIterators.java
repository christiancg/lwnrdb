package org.techhouse.simplejs.builtins;

import java.util.Iterator;
import java.util.function.IntFunction;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;

public final class JsIterators {
    private JsIterators() {
    }

    /**
     * Builds an iterator object ({@code {next(): {value, done}}}) that is itself iterable
     * ({@code [Symbol.iterator]() {return this}}), driving the supplied Java iterator.
     */
    public static JsObject of(Iterator<org.techhouse.simplejs.values.JsValue> source) {
        final var iterator = new JsObject();
        iterator.set("next", new JsNativeFunction("next", (_, _) -> {
            final var step = new JsObject();
            if (source.hasNext()) {
                step.set("value", source.next());
                step.set("done", JsBoolean.FALSE);
            } else {
                step.set("value", JsUndefined.getInstance());
                step.set("done", JsBoolean.TRUE);
            }
            return step;
        }));
        iterator.setSymbol(JsSymbol.ITERATOR, new JsNativeFunction("[Symbol.iterator]", (_, _) -> iterator));
        return iterator;
    }

    /**
     * Builds the same iterator object over a live source read one index at a time, so a mutation
     * made between two {@code next()} calls is observed. The step function returns {@code null}
     * once the index is past the end.
     */
    public static JsObject lazy(IntFunction<org.techhouse.simplejs.values.JsValue> step) {
        final var cursor = new int[1];
        final var iterator = new JsObject();
        iterator.set("next", new JsNativeFunction("next", (_, _) -> {
            final var result = new JsObject();
            final var value = step.apply(cursor[0]);
            if (value == null) {
                result.set("value", JsUndefined.getInstance());
                result.set("done", JsBoolean.TRUE);
            } else {
                cursor[0]++;
                result.set("value", value);
                result.set("done", JsBoolean.FALSE);
            }
            return result;
        }));
        iterator.setSymbol(JsSymbol.ITERATOR, new JsNativeFunction("[Symbol.iterator]", (_, _) -> iterator));
        return iterator;
    }
}

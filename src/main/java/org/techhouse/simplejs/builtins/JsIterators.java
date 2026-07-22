package org.techhouse.simplejs.builtins;

import java.util.Iterator;
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
}

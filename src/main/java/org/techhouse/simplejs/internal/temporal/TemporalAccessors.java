package org.techhouse.simplejs.internal.temporal;

import java.util.function.Function;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsValue;

public final class TemporalAccessors {
    public static void installGetter(JsObject proto, String name, Function<JsValue, JsValue> impl) {
        final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> impl.apply(thisArg));
        getter.setLength(0);
        proto.defineAccessor(name, getter, null);
        proto.setFlags(name, JsObject.PropertyFlags.HIDDEN);
    }

    private TemporalAccessors() {
    }
}

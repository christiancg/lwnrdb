package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.values.JsObject.PropertyFlags.HIDDEN;
import static org.techhouse.simplejs.values.JsObject.PropertyFlags.TAG;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class JsIterators {
    private static final Map<JsObject, Supplier<JsValue>> STATE = Collections.synchronizedMap(new WeakHashMap<>());

    private JsIterators() {
    }

    public static JsObject of(Iterator<JsValue> source) {
        return instance(() -> source.hasNext() ? source.next() : null);
    }

    public static JsObject lazy(IntFunction<JsValue> step) {
        final var cursor = new int[1];
        return instance(() -> {
            final var value = step.apply(cursor[0]);
            if (value == null) {
                return null;
            }
            cursor[0]++;
            return value;
        });
    }

    public static JsObject prototype(String tag, JsObject objectProto) {
        final var proto = new JsObject();
        final var next = new JsNativeFunction("next", (thisArg, _) -> step(thisArg, tag, objectProto));
        next.setLength(0);
        proto.defineValue("next", next);
        proto.setFlags("next", HIDDEN);
        proto.setSymbol(JsSymbol.TO_STRING_TAG, new JsString(tag));
        proto.setSymbolFlags(JsSymbol.TO_STRING_TAG, TAG);
        proto.setProto(objectProto);
        return proto;
    }

    public static JsValue linkPrototype(JsValue value, JsObject proto) {
        if (proto != null && value instanceof JsObject object && STATE.containsKey(object)) {
            object.setProto(proto);
        }
        return value;
    }

    private static JsObject instance(Supplier<JsValue> stepper) {
        final var iterator = new JsObject();
        final var done = new boolean[]{false};
        STATE.put(iterator, () -> {
            if (done[0]) {
                return null;
            }
            final var value = stepper.get();
            done[0] = value == null;
            return value;
        });
        return iterator;
    }

    private static JsValue step(JsValue receiver, String tag, JsObject objectProto) {
        final var stepper = receiver instanceof JsObject object ? STATE.get(object) : null;
        if (stepper == null) {
            throw new TypeErrorException(tag + ".next called on an incompatible receiver");
        }
        final var value = stepper.get();
        final var result = new JsObject();
        result.setProto(objectProto);
        result.set("value", value == null ? JsUndefined.getInstance() : value);
        result.set("done", JsBoolean.of(value == null));
        return result;
    }
}

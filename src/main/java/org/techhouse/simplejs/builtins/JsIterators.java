package org.techhouse.simplejs.builtins;

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
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class JsIterators {
    // The instances' [[IteratedObject]]-equivalent internal slot. Held off the object (identity-keyed,
    // weak) rather than under a private property key so `next` can live on the realm's prototype and
    // brand-check its receiver without the slot ever becoming observable from script.
    private static final Map<JsObject, Supplier<JsValue>> STATE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final PropertyFlags HIDDEN = new PropertyFlags(true, false, true);
    private static final PropertyFlags TAG = new PropertyFlags(false, false, true);

    private JsIterators() {
    }

    /**
     * Builds a built-in iterator instance driving the supplied Java iterator. It carries no own
     * property until {@link #linkPrototype} attaches the realm's prototype, which owns {@code next}.
     */
    public static JsObject of(Iterator<JsValue> source) {
        return instance(() -> source.hasNext() ? source.next() : null);
    }

    /**
     * Builds the same iterator over a live source read one index at a time, so a mutation made
     * between two {@code next()} calls is observed. The step function returns {@code null} once the
     * index is past the end.
     */
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

    /**
     * Builds one of the realm's built-in iterator prototypes ({@code %ArrayIteratorPrototype%} and
     * friends): a brand-checking {@code next} plus the non-writable, configurable {@code @@toStringTag}.
     */
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

    // An exhausted iterator stays exhausted: once next() answered done, a later append to the live
    // source must not resurrect it.
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

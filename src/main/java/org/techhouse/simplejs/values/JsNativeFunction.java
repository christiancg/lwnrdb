package org.techhouse.simplejs.values;

import java.util.List;
import java.util.function.BiFunction;

public final class JsNativeFunction extends JsValue implements JsCallableProperties {
    private static final ThreadLocal<JsValue> NEW_TARGET = new ThreadLocal<>();

    private final String name;
    private final BiFunction<JsValue, List<JsValue>, JsValue> implementation;
    private final CallableMetadata metadata = new CallableMetadata();
    private JsValue boundTarget;
    private List<JsValue> boundArgs;
    private JsValue prototype;
    private JsValue ownProto;
    private double explicitLength = -1;
    private boolean constructor;

    public JsNativeFunction(String name, BiFunction<JsValue, List<JsValue>, JsValue> implementation) {
        this.name = name;
        this.implementation = implementation;
    }

    public boolean isConstructor() {
        return constructor;
    }

    public void markConstructor() {
        constructor = true;
    }

    public void setLength(int length) {
        this.explicitLength = length;
    }

    public void setLength(double length) {
        this.explicitLength = length;
    }

    public boolean hasExplicitLength() {
        return explicitLength >= 0;
    }

    public int getExplicitLength() {
        return (int) explicitLength;
    }

    public double getExplicitLengthValue() {
        return explicitLength;
    }

    public String getName() {
        return name;
    }

    public void setBound(JsValue boundTarget, List<JsValue> boundArgs) {
        this.boundTarget = boundTarget;
        this.boundArgs = boundArgs;
    }

    public boolean isBound() {
        return boundTarget != null;
    }

    public JsValue getBoundTarget() {
        return boundTarget;
    }

    public List<JsValue> getBoundArgs() {
        return boundArgs;
    }

    public static JsValue currentNewTarget() {
        return NEW_TARGET.get();
    }

    public JsValue invoke(JsValue thisArg, List<JsValue> args) {
        final var previous = NEW_TARGET.get();
        if (previous == null) {
            return implementation.apply(thisArg, args);
        }
        NEW_TARGET.remove();
        try {
            return implementation.apply(thisArg, args);
        } finally {
            NEW_TARGET.set(previous);
        }
    }

    public JsValue invoke(JsValue thisArg, List<JsValue> args, JsValue newTarget) {
        final var previous = NEW_TARGET.get();
        NEW_TARGET.set(newTarget);
        try {
            return implementation.apply(thisArg, args);
        } finally {
            if (previous == null) {
                NEW_TARGET.remove();
            } else {
                NEW_TARGET.set(previous);
            }
        }
    }

    @Override
    public CallableMetadata callableMetadata() {
        return metadata;
    }

    @Override
    public PropertyTable ownProperties() {
        return metadata.table();
    }

    @Override
    public boolean deleteOwnProperty(JsValue key) {
        return switch (metadata.deleteOwn(key, _ -> getPrototype() != null)) {
            case DELETED -> true;
            case REJECTED -> false;
            case ORDINARY -> super.deleteOwnProperty(key);
        };
    }

    public JsValue getPrototype() {
        return prototype;
    }

    public void setPrototype(JsValue prototype) {
        this.prototype = prototype;
    }

    public JsValue getOwnProto() {
        return ownProto;
    }

    public void setOwnProto(JsValue ownProto) {
        this.ownProto = ownProto;
    }
}

package org.techhouse.simplejs.values;

import java.util.List;
import java.util.function.BiFunction;
import org.techhouse.simplejs.internal.Coroutine;

public final class JsNativeFunction extends JsValue implements JsCallableProperties {
    private final String name;
    private final BiFunction<JsValue, List<JsValue>, JsValue> implementation;
    private final NativeConstructor constructorImplementation;
    private final CallerAwareFunction callerAwareImplementation;
    private final CallableMetadata metadata = new CallableMetadata();
    private JsValue boundTarget;
    private List<JsValue> boundArgs;
    private JsValue prototype;
    private JsValue ownProto;
    private double explicitLength = -1;
    private boolean constructor;

    public JsNativeFunction(String name, BiFunction<JsValue, List<JsValue>, JsValue> implementation) {
        this(name, implementation, null, null);
    }

    public JsNativeFunction(String name, NativeConstructor constructorImplementation) {
        this(name, null, constructorImplementation, null);
    }

    private JsNativeFunction(String name, BiFunction<JsValue, List<JsValue>, JsValue> implementation,
            NativeConstructor constructorImplementation, CallerAwareFunction callerAwareImplementation) {
        this.name = name;
        this.implementation = implementation;
        this.constructorImplementation = constructorImplementation;
        this.callerAwareImplementation = callerAwareImplementation;
    }

    public static JsNativeFunction callerAware(String name, CallerAwareFunction implementation) {
        return new JsNativeFunction(name, null, null, implementation);
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

    public JsValue invoke(JsValue thisArg, List<JsValue> args) {
        return invoke(thisArg, args, JsUndefined.getInstance());
    }

    public JsValue invoke(JsValue thisArg, List<JsValue> args, JsValue newTarget) {
        if (constructorImplementation != null) {
            return constructorImplementation.construct(thisArg, args, newTarget);
        }
        if (callerAwareImplementation != null) {
            return callerAwareImplementation.call(thisArg, args, null);
        }
        return implementation.apply(thisArg, args);
    }

    public JsValue invokeFromCaller(JsValue thisArg, List<JsValue> args, Coroutine caller) {
        if (callerAwareImplementation != null) {
            return callerAwareImplementation.call(thisArg, args, caller);
        }
        return invoke(thisArg, args);
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

    @Override
    public JsValueType getType() {
        return JsValueType.FUNCTION;
    }
}

package org.techhouse.simplejs.values;

public final class JsProxy extends JsValue {
    private final JsValue target;
    private final JsValue handler;
    private boolean revoked;

    // Target and handler are any object, a revoked proxy included: ProxyCreate only rejects a
    // primitive, and a revoked handler surfaces later as a TypeError from the trap lookup.
    public JsProxy(JsValue target, JsValue handler) {
        this.target = target;
        this.handler = handler;
    }

    public JsValue getTarget() {
        return target;
    }

    public JsValue getHandler() {
        return handler;
    }

    public void revoke() {
        revoked = true;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public boolean isCallable() {
        return target instanceof JsFunction || target instanceof JsNativeFunction || target instanceof JsClass
                || (target instanceof JsProxy proxy && proxy.isCallable());
    }

    public boolean isConstructor() {
        return switch (target) {
            case JsClass ignored -> true;
            case JsNativeFunction nativeFunction -> nativeFunction.isConstructor();
            case JsFunction function -> function.isConstructor();
            case JsProxy proxy -> proxy.isConstructor();
            default -> false;
        };
    }
}

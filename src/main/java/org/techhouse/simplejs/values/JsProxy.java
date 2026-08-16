package org.techhouse.simplejs.values;

public final class JsProxy extends JsValue {
    private final JsValue target;
    private final JsObject handler;
    private boolean revoked;

    public JsProxy(JsValue target, JsObject handler) {
        this.target = target;
        this.handler = handler;
    }

    public JsValue getTarget() {
        return target;
    }

    public JsObject getHandler() {
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

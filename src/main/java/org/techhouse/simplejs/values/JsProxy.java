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
        return target instanceof JsFunction || target instanceof JsNativeFunction || target instanceof JsClass;
    }
}

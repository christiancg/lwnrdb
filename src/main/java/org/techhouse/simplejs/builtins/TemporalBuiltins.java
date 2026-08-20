package org.techhouse.simplejs.builtins;

import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;

/**
 * {@code Temporal} is a namespace object whose own properties are themselves constructors (a
 * pattern not used anywhere else - {@code Math}/{@code JSON}/{@code Reflect} are namespaces of
 * functions only). This installs just {@code Temporal.PlainTime} for now (Phase T2); the other
 * Temporal types land here from their own phases and get merged into this one file.
 */
public final class TemporalBuiltins {
    private TemporalBuiltins() {
    }

    public static JsObject install(Environment global, Intrinsics intrinsics, InterpreterOps ops) {
        final var temporal = new JsObject();
        temporal.setProto(intrinsics.objectProto());
        installCtor(temporal, "PlainTime", TemporalPlainTimeBuiltins.create(ops), intrinsics.temporalPlainTimeProto());
        global.declareBuiltin("Temporal", temporal);
        return temporal;
    }

    // GlobalScope.constructor()'s logic, retargeted to write onto a JsObject property instead of an
    // Environment binding (Temporal's constructors live on the Temporal namespace object, not
    // directly on the global environment).
    private static void installCtor(JsObject namespaceObject, String name, JsNativeFunction ctor, JsObject proto) {
        ctor.setPrototype(proto);
        ctor.markConstructor();
        proto.defineValue("constructor", ctor);
        proto.setFlags("constructor", new JsObject.PropertyFlags(true, false, true));
        namespaceObject.defineValue(name, ctor);
        namespaceObject.setFlags(name, new JsObject.PropertyFlags(true, false, true));
    }
}

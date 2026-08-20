package org.techhouse.simplejs.builtins;

import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;

// The `Temporal` namespace's own properties are themselves constructors (PlainDate, Duration, ...) -
// unlike Math/JSON/Reflect (namespaces of functions only) or Number/Date (constructors that are also
// namespaces of statics). Each phase adds its own installCtor(...) call here; the orchestrating
// session reconciles the several parallel-phase variants of this file at merge time.
public final class TemporalBuiltins {
    private TemporalBuiltins() {
    }

    public static JsObject install(Environment global, Intrinsics intrinsics, InterpreterOps ops) {
        final var temporal = new JsObject();
        temporal.setProto(intrinsics.objectProto());
        installCtor(temporal, "PlainDate", TemporalPlainDateBuiltins.create(ops), intrinsics.temporalPlainDateProto());
        global.declareBuiltin("Temporal", temporal);
        return temporal;
    }

    // GlobalScope.constructor()'s logic, retargeted to write onto a JsObject property instead of an
    // Environment binding (Temporal is not itself a global binding per constructor, just a namespace
    // object holding these).
    private static void installCtor(JsObject namespace, String name, JsNativeFunction ctor, JsObject proto) {
        ctor.setPrototype(proto);
        ctor.markConstructor();
        proto.defineValue("constructor", ctor);
        proto.setFlags("constructor", new JsObject.PropertyFlags(true, false, true));
        namespace.defineValue(name, ctor);
        namespace.setFlags(name, new JsObject.PropertyFlags(true, false, true));
    }
}

package org.techhouse.simplejs.builtins;

import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;

// Temporal is a namespace object whose own properties are themselves constructors - unlike Math/
// JSON/Reflect (namespaces of functions only) or Number/Date/etc. (constructors that are also
// namespaces of statics). Each Temporal.* type lands here as one installCtor(...) call as its own
// phase merges in.
public final class TemporalBuiltins {
    private TemporalBuiltins() {
    }

    public static JsObject install(Environment global, Intrinsics intrinsics, InterpreterOps ops) {
        final var temporal = new JsObject();
        temporal.setProto(intrinsics.objectProto());
        installCtor(temporal, "Duration", TemporalDurationBuiltins.create(ops), intrinsics.temporalDurationProto());
        installCtor(temporal, "PlainTime", TemporalPlainTimeBuiltins.create(ops), intrinsics.temporalPlainTimeProto());
        installCtor(temporal, "PlainDate", TemporalPlainDateBuiltins.create(ops), intrinsics.temporalPlainDateProto());
        global.declareBuiltin("Temporal", temporal);
        return temporal;
    }

    // GlobalScope.constructor(...)'s logic, retargeted to write onto a JsObject property instead of
    // an Environment binding (Temporal itself is not a global binding target - only its members are).
    private static void installCtor(JsObject temporal, String name, JsNativeFunction ctor, JsObject proto) {
        ctor.setPrototype(proto);
        ctor.markConstructor();
        proto.defineValue("constructor", ctor);
        proto.setFlags("constructor", new JsObject.PropertyFlags(true, false, true));
        temporal.defineValue(name, ctor);
        temporal.setFlags(name, new JsObject.PropertyFlags(true, false, true));
    }
}

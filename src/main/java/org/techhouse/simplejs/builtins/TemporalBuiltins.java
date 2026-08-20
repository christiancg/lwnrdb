package org.techhouse.simplejs.builtins;

import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;

/**
 * The {@code Temporal} namespace object: unlike {@code Math}/{@code JSON}/{@code Reflect} (namespaces
 * of functions only), its own properties are themselves constructors. This installer is deliberately
 * minimal - it only wires {@code Temporal.Instant} (phase T6). The other {@code Temporal.*}
 * constructors land here from their own concurrently-developed phases (T1-T5, T7-T8), each adding one
 * more {@link #installCtor} call; the several per-phase copies of this file are meant to be merged
 * together, not layered.
 */
public final class TemporalBuiltins {
    private TemporalBuiltins() {
    }

    public static JsObject install(Environment global, Intrinsics intrinsics, InterpreterOps ops) {
        final var temporal = new JsObject();
        temporal.setProto(intrinsics.objectProto());
        installCtor(temporal, "Instant", TemporalInstantBuiltins.create(ops), intrinsics.temporalInstantProto());
        global.declareBuiltin("Temporal", temporal);
        return temporal;
    }

    // GlobalScope.constructor()'s logic, retargeted to write onto a JsObject property instead of an
    // Environment binding (Temporal is a namespace object, not itself a global binding per constructor).
    private static void installCtor(JsObject temporal, String name, JsNativeFunction ctor, JsObject proto) {
        ctor.setPrototype(proto);
        ctor.markConstructor();
        proto.defineValue("constructor", ctor);
        proto.setFlags("constructor", new JsObject.PropertyFlags(true, false, true));
        temporal.defineValue(name, ctor);
        temporal.setFlags(name, new JsObject.PropertyFlags(true, false, true));
    }
}

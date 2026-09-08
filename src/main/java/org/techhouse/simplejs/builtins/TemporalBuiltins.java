package org.techhouse.simplejs.builtins;

import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;

public final class TemporalBuiltins {
    private TemporalBuiltins() {
    }

    public static JsObject install(Environment global, Intrinsics intrinsics, InterpreterOps ops) {
        final var temporal = new JsObject();
        temporal.setProto(intrinsics.objectProto);
        installCtor(temporal, "Duration", TemporalDurationBuiltins.create(ops), intrinsics.temporalDurationProto);
        installCtor(temporal, "PlainTime", TemporalPlainTimeBuiltins.create(ops), intrinsics.temporalPlainTimeProto);
        installCtor(temporal, "PlainDate", TemporalPlainDateBuiltins.create(ops), intrinsics.temporalPlainDateProto);
        installCtor(temporal, "Instant", TemporalInstantBuiltins.create(ops), intrinsics.temporalInstantProto);
        installCtor(temporal, "PlainYearMonth", TemporalPlainYearMonthBuiltins.create(ops),
                intrinsics.temporalPlainYearMonthProto);
        installCtor(temporal, "PlainMonthDay", TemporalPlainMonthDayBuiltins.create(ops),
                intrinsics.temporalPlainMonthDayProto);
        installCtor(temporal, "PlainDateTime", TemporalPlainDateTimeBuiltins.create(ops),
                intrinsics.temporalPlainDateTimeProto);
        installCtor(temporal, "ZonedDateTime", TemporalZonedDateTimeBuiltins.create(ops),
                intrinsics.temporalZonedDateTimeProto);
        final var now = new JsObject();
        now.setProto(intrinsics.objectProto);
        TemporalNowBuiltins.install(now, ops);
        Intrinsics.defineNamespaceTag(now, "Temporal.Now");
        temporal.defineValue("Now", now);
        temporal.setFlags("Now", JsObject.PropertyFlags.HIDDEN);
        Intrinsics.defineNamespaceTag(temporal, "Temporal");
        global.declareBuiltin("Temporal", temporal);
        return temporal;
    }

    private static void installCtor(JsObject temporal, String name, JsNativeFunction ctor, JsObject proto) {
        ctor.setPrototype(proto);
        ctor.markConstructor();
        proto.defineValue("constructor", ctor);
        proto.setFlags("constructor", JsObject.PropertyFlags.HIDDEN);
        temporal.defineValue(name, ctor);
        temporal.setFlags(name, JsObject.PropertyFlags.HIDDEN);
    }
}

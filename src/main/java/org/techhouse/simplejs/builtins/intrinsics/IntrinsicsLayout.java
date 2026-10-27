package org.techhouse.simplejs.builtins.intrinsics;

import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireDbDateTime;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireDbTime;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireGeo;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalDuration;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalInstant;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalPlainDate;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalPlainDateTime;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalPlainMonthDay;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalPlainTime;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalPlainYearMonth;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalZonedDateTime;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireVector;
import static org.techhouse.simplejs.values.JsObject.PropertyFlags.HIDDEN;

import java.util.List;
import java.util.function.Consumer;
import org.techhouse.simplejs.builtins.DbDateTimeBuiltins;
import org.techhouse.simplejs.builtins.DbTimeBuiltins;
import org.techhouse.simplejs.builtins.GeoBuiltins;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.Intrinsics.MethodResolver;
import org.techhouse.simplejs.builtins.TemporalDurationBuiltins;
import org.techhouse.simplejs.builtins.TemporalInstantBuiltins;
import org.techhouse.simplejs.builtins.TemporalPlainDateBuiltins;
import org.techhouse.simplejs.builtins.TemporalPlainDateTimeBuiltins;
import org.techhouse.simplejs.builtins.TemporalPlainMonthDayBuiltins;
import org.techhouse.simplejs.builtins.TemporalPlainTimeBuiltins;
import org.techhouse.simplejs.builtins.TemporalPlainYearMonthBuiltins;
import org.techhouse.simplejs.builtins.TemporalZonedDateTimeBuiltins;
import org.techhouse.simplejs.builtins.VectorBuiltins;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;

public record IntrinsicsLayout(Intrinsics intrinsics) {
    private JsObject lazy(List<String> names, String label, MethodResolver resolver, Consumer<JsObject> extra) {
        final var proto = intrinsics.coercion.prototypeOf(names, label, resolver);
        proto.deferSetup(extra);
        return proto;
    }

    private static Consumer<JsObject> tagged(Consumer<JsObject> accessors, String tag) {
        return proto -> {
            accessors.accept(proto);
            IntrinsicsInstallers.defineToStringTag(proto, tag);
        };
    }

    public JsObject temporalDuration() {
        final var proto = new JsObject();
        proto.setProto(intrinsics.objectProto);
        proto.deferSetup(target -> {
            for (final var name : TemporalDurationBuiltins.ACCESSOR_NAMES) {
                final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> TemporalDurationBuiltins
                        .fieldAccessor(requireTemporalDuration(thisArg, name), name));
                getter.setLength(0);
                target.defineAccessor(name, getter, null);
                target.setFlags(name, HIDDEN);
            }
            intrinsics.coercion.installMethods(target, TemporalDurationBuiltins.METHOD_NAMES,
                    "Temporal.Duration.prototype", (receiver, key) -> TemporalDurationBuiltins
                            .getMethod(requireTemporalDuration(receiver, key), key, intrinsics.ops));
        });
        return proto;
    }

    public JsObject temporalPlainTime() {
        return lazy(TemporalPlainTimeBuiltins.NAMES, "Temporal.PlainTime.prototype",
                (receiver, name) -> TemporalPlainTimeBuiltins.getMethod(requireTemporalPlainTime(receiver, name), name,
                        intrinsics.ops),
                tagged(intrinsics.installers::installTemporalPlainTimeAccessors, "Temporal.PlainTime"));
    }

    public JsObject temporalPlainDate() {
        return lazy(TemporalPlainDateBuiltins.NAMES, "Temporal.PlainDate.prototype",
                (receiver, name) -> TemporalPlainDateBuiltins.getMethod(requireTemporalPlainDate(receiver, name), name,
                        intrinsics.ops),
                TemporalPlainDateBuiltins::installAccessors);
    }

    public JsObject temporalInstant() {
        return lazy(
                TemporalInstantBuiltins.NAMES, "Temporal.Instant.prototype", (receiver, name) -> TemporalInstantBuiltins
                        .getMethod(requireTemporalInstant(receiver, name), name, intrinsics.ops),
                TemporalInstantBuiltins::installAccessors);
    }

    public JsObject temporalPlainYearMonth() {
        return lazy(TemporalPlainYearMonthBuiltins.NAMES, "Temporal.PlainYearMonth.prototype",
                (receiver, name) -> TemporalPlainYearMonthBuiltins
                        .getMethod(requireTemporalPlainYearMonth(receiver, name), name, intrinsics.ops),
                tagged(TemporalPlainYearMonthBuiltins::installAccessors, "Temporal.PlainYearMonth"));
    }

    public JsObject temporalPlainMonthDay() {
        return lazy(TemporalPlainMonthDayBuiltins.NAMES, "Temporal.PlainMonthDay.prototype",
                (receiver, name) -> TemporalPlainMonthDayBuiltins
                        .getMethod(requireTemporalPlainMonthDay(receiver, name), name, intrinsics.ops),
                tagged(TemporalPlainMonthDayBuiltins::installAccessors, "Temporal.PlainMonthDay"));
    }

    public JsObject temporalPlainDateTime() {
        return lazy(TemporalPlainDateTimeBuiltins.NAMES, "Temporal.PlainDateTime.prototype",
                (receiver, name) -> TemporalPlainDateTimeBuiltins
                        .getMethod(requireTemporalPlainDateTime(receiver, name), name, intrinsics.ops),
                TemporalPlainDateTimeBuiltins::installAccessors);
    }

    public JsObject temporalZonedDateTime() {
        return lazy(TemporalZonedDateTimeBuiltins.NAMES, "Temporal.ZonedDateTime.prototype",
                (receiver, name) -> TemporalZonedDateTimeBuiltins
                        .getMethod(requireTemporalZonedDateTime(receiver, name), name, intrinsics.ops),
                TemporalZonedDateTimeBuiltins::installAccessors);
    }

    public JsObject geo() {
        return lazy(GeoBuiltins.NAMES, "Geo.prototype",
                (receiver, name) -> GeoBuiltins.getMethod(requireGeo(receiver, name), name),
                tagged(GeoBuiltins::installAccessors, "Geo"));
    }

    public JsObject vector() {
        return lazy(VectorBuiltins.NAMES, "Vector.prototype",
                (receiver, name) -> VectorBuiltins.getMethod(requireVector(receiver, name), name, intrinsics.ops),
                tagged(VectorBuiltins::installAccessors, "Vector"));
    }

    public JsObject dbDateTime() {
        return lazy(DbDateTimeBuiltins.NAMES, "DbDateTime.prototype",
                (receiver, name) -> DbDateTimeBuiltins.getMethod(requireDbDateTime(receiver, name), name),
                tagged(DbDateTimeBuiltins::installAccessors, "DbDateTime"));
    }

    public JsObject dbTime() {
        return lazy(DbTimeBuiltins.NAMES, "DbTime.prototype",
                (receiver, name) -> DbTimeBuiltins.getMethod(requireDbTime(receiver, name), name),
                tagged(DbTimeBuiltins::installAccessors, "DbTime"));
    }
}

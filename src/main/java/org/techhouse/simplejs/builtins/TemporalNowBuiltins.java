package org.techhouse.simplejs.builtins;

import java.time.ZoneId;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * {@code Temporal.Now} - a plain namespace object of functions, not a constructor, installed the
 * same way {@code Math}/{@code JSON}/{@code Reflect} are (see {@link Intrinsics#installMethod}).
 * Every member reads wall-clock time via {@code System.currentTimeMillis()}, mirroring
 * {@code Date.now()}'s existing precedent (see {@code builtins/DateBuiltins}) rather than reaching
 * for {@code java.time.Instant.now()}'s finer platform-dependent precision: consistency with the
 * rest of the engine's single time source matters more here than sub-millisecond precision the JVM
 * is not guaranteed to reliably provide anyway. Zone-aware members default to
 * the host's time zone ({@link InterpreterOps#timeZone}) when no {@code temporalTimeZoneLike} argument is
 * given.
 */
public final class TemporalNowBuiltins {
    private record TimeZoneRef(ZoneId zone, String id) {
    }

    private TemporalNowBuiltins() {
    }

    public static void install(JsObject now, InterpreterOps ops) {
        Intrinsics.installMethod(now, "instant", new JsNativeFunction("instant", (_, _) -> instant()));
        Intrinsics.installMethod(now, "timeZoneId",
                new JsNativeFunction("timeZoneId", (_, _) -> new JsString(InterpreterOps.timeZone(ops).getId())));
        Intrinsics.installMethod(now, "plainDateISO", new JsNativeFunction("plainDateISO",
                (_, args) -> new JsTemporalPlainDate(fieldsAt(resolveTimeZone(args, ops)).date())));
        Intrinsics.installMethod(now, "plainTimeISO", new JsNativeFunction("plainTimeISO",
                (_, args) -> new JsTemporalPlainTime(fieldsAt(resolveTimeZone(args, ops)).time())));
        Intrinsics.installMethod(now, "plainDateTimeISO", new JsNativeFunction("plainDateTimeISO", (_, args) -> {
            final var fields = fieldsAt(resolveTimeZone(args, ops));
            return new JsTemporalPlainDateTime(fields.date(), fields.time());
        }));
        Intrinsics.installMethod(now, "zonedDateTimeISO",
                new JsNativeFunction("zonedDateTimeISO", (_, args) -> zonedDateTimeISO(resolveTimeZone(args, ops))));
    }

    // Date.now()'s own source of "now" - see the class-level note on why this is not
    // java.time.Instant.now().
    private static JsTemporalInstant instant() {
        return JsTemporalInstant.fromEpochMilliseconds(System.currentTimeMillis());
    }

    private static JsTemporalInstant.IsoFieldsAt fieldsAt(TimeZoneRef ref) {
        final var current = instant();
        final var offset = ref.zone().getRules().getOffset(current.toJavaInstant());
        return current.isoFieldsAt(offset);
    }

    private static JsValue zonedDateTimeISO(TimeZoneRef ref) {
        final var current = instant();
        return new JsTemporalZonedDateTime(current.epochSecondsPart(), current.nanoAdjustment(), ref.zone(), ref.id());
    }

    // ToTemporalTimeZoneIdentifier: an omitted/undefined argument is the host's time zone; a
    // ZonedDateTime-like argument (including a subclass wrapper) reuses its own time zone; anything
    // else is coerced to a string and parsed as a time zone identifier, mirroring
    // TemporalInstantBuiltins/TemporalZonedDateTimeBuiltins' own zoneOf helpers.
    private static TimeZoneRef resolveTimeZone(List<JsValue> args, InterpreterOps ops) {
        final var arg = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        if (arg instanceof JsUndefined) {
            final var zone = InterpreterOps.timeZone(ops);
            return new TimeZoneRef(zone, zone.getId());
        }
        final var zoned = asZonedDateTime(arg);
        if (zoned != null) {
            return new TimeZoneRef(zoned.zone(), zoned.timeZoneId());
        }
        if (!(arg instanceof JsString s)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        final var id = TemporalParser.parseTimeZoneIdentifierFlexible(s.getValue());
        return new TimeZoneRef(TemporalZonedDateTimeBuiltins.zoneOf(id), id);
    }

    private static JsTemporalZonedDateTime asZonedDateTime(JsValue value) {
        if (value instanceof JsTemporalZonedDateTime zoned) {
            return zoned;
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalZonedDateTime zoned) {
            return zoned;
        }
        return null;
    }
}

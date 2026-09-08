package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MIN_ISO_DATE;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.TemporalZonedDateTimeBuiltins.OffsetOption;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.Disambiguation;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TimeZoneStringParser;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsValue;

public final class ZonedDateTimeZones {
    public static ZoneId zoneOf(String identifier) {
        if (!identifier.isEmpty() && (identifier.charAt(0) == '+' || identifier.charAt(0) == '-')) {
            return toZoneOffset(identifier);
        }
        if (TemporalCalendarIdentifier.asciiEqualsIgnoreCase(identifier, "utc")) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(identifier);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid time zone identifier: " + identifier);
        }
    }

    public static ZoneOffset toZoneOffset(String offsetText) {
        if ("Z".equals(offsetText) || "z".equals(offsetText)) {
            return ZoneOffset.UTC;
        }
        TimeZoneStringParser.requireValidUtcOffset(offsetText);
        try {
            var normalized = offsetText;
            final var dot = normalized.indexOf('.');
            final var comma = normalized.indexOf(',');
            final var fractionStart = dot < 0 ? comma : (comma < 0 ? dot : Math.min(dot, comma));
            if (fractionStart >= 0) {
                normalized = normalized.substring(0, fractionStart);
            }
            return ZoneOffset.of(normalized);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid UTC offset: " + offsetText);
        }
    }

    public static ZonedDateTime resolveLocal(LocalDateTime local, ZoneId zone, Disambiguation disambiguation) {
        final var transition = zone.getRules().getTransition(local);
        if (transition == null) {
            return ZonedDateTime.of(local, zone);
        }
        if (transition.isGap()) {
            return switch (disambiguation) {
                case REJECT -> throw new RangeErrorException(
                        "Temporal.ZonedDateTime: local time falls in a time zone transition gap and "
                                + "disambiguation is 'reject'");
                case EARLIER -> local.toInstant(transition.getOffsetAfter()).atZone(zone);
                case LATER, COMPATIBLE -> local.toInstant(transition.getOffsetBefore()).atZone(zone);
            };
        }
        return switch (disambiguation) {
            case REJECT -> throw new RangeErrorException(
                    "Temporal.ZonedDateTime: local time is ambiguous (time zone transition fold) and "
                            + "disambiguation is 'reject'");
            case LATER -> local.toInstant(transition.getOffsetAfter()).atZone(zone);
            case EARLIER, COMPATIBLE -> local.toInstant(transition.getOffsetBefore()).atZone(zone);
        };
    }

    public static boolean isMidnight(IsoTimeFields time) {
        return time.hour() == 0 && time.minute() == 0 && time.second() == 0 && time.millisecond() == 0
                && time.microsecond() == 0 && time.nanosecond() == 0;
    }

    public static JsTemporalZonedDateTime resolveToZoned(Iso8601Fields date, IsoTimeFields time, ZoneId zone,
            String timeZoneId, Disambiguation disambiguation) {
        return resolveToZonedWithOffset(date, time, zone, timeZoneId, null, disambiguation, OffsetOption.IGNORE);
    }

    public static ZonedDateTime interpretOffset(LocalDateTime local, ZoneId zone, String offsetText,
            Disambiguation disambiguation, OffsetOption offsetOption) {
        if (offsetText == null || offsetOption == OffsetOption.IGNORE) {
            return resolveLocal(local, zone, disambiguation);
        }
        final var explicitOffset = toZoneOffset(offsetText);
        if (offsetOption == OffsetOption.USE) {
            return local.atZone(explicitOffset).withZoneSameInstant(zone);
        }
        if (zone.getRules().getValidOffsets(local).contains(explicitOffset)) {
            return local.atZone(explicitOffset).withZoneSameInstant(zone);
        }
        if (offsetOption == OffsetOption.REJECT) {
            throw new RangeErrorException(
                    "offset " + offsetText + " does not match time zone " + zone + " at " + local);
        }
        return resolveLocal(local, zone, disambiguation);
    }

    public static JsTemporalZonedDateTime resolveToZonedWithOffset(Iso8601Fields date, IsoTimeFields time, ZoneId zone,
            String timeZoneId, String offsetText, Disambiguation disambiguation, OffsetOption offsetOption) {
        if (date.equals(MIN_ISO_DATE) && isMidnight(time)) {
            throw new RangeErrorException("date-time value is outside the representable range");
        }
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final LocalDateTime local;
        try {
            local = LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(), time.second(),
                    nanoOfSecond);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid Temporal.ZonedDateTime fields: " + e.getMessage());
        }
        final var resolved = interpretOffset(local, zone, offsetText, disambiguation, offsetOption);
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(resolved));
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(resolved, timeZoneId);
    }

    public static BigInteger epochNanosOf(ZonedDateTime zdt) {
        return BigInteger.valueOf(zdt.toEpochSecond()).multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(zdt.getNano()));
    }

    public static ZonedDateTime resolveToZonedDateTime(Iso8601Fields date, IsoTimeFields time, ZoneId zone) {
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final LocalDateTime local;
        try {
            local = LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(), time.second(),
                    nanoOfSecond);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid Temporal.ZonedDateTime fields: " + e.getMessage());
        }
        return resolveLocal(local, zone, Disambiguation.COMPATIBLE);
    }

    public static JsValue getTimeZoneTransition(JsTemporalZonedDateTime receiver, JsValue directionArg,
            InterpreterOps ops) {
        final JsValue directionValue;
        if (directionArg instanceof JsString) {
            directionValue = directionArg;
        } else if (InterpreterUtils.isObjectLike(directionArg)) {
            directionValue = ops.getMember(directionArg, new JsString("direction"));
        } else {
            throw new TypeErrorException(
                    "Temporal.ZonedDateTime.prototype.getTimeZoneTransition argument must be a string or an object");
        }
        final var direction = JsCoercion.toStr(directionValue, ops);
        final var rules = receiver.zone().getRules();
        final var transition = switch (direction) {
            case "next" -> rules.nextTransition(receiver.toJavaInstant());
            case "previous" -> rules.previousTransition(receiver.toJavaInstant());
            default -> throw new RangeErrorException("getTimeZoneTransition direction must be 'next' or 'previous'");
        };
        if (transition == null) {
            return JsNull.getInstance();
        }
        final var instant = transition.getInstant();
        return new JsTemporalZonedDateTime(instant.getEpochSecond(), instant.getNano(), receiver.zone(),
                receiver.timeZoneId());
    }

    private ZonedDateTimeZones() {
    }
}

package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.TemporalPlainDateTimeBuiltins.dateTime;
import static org.techhouse.simplejs.builtins.TemporalPlainTimeBuiltins.fromNanosOfDay;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.pad2;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.optionOrUndefined;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readCalendarNameOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readDisambiguationOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readIncrementOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readRoundingModeOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.smallestUnitOptions;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.validateRoundingIncrement;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.nanosPerUnit;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.toNanosOfDay;
import static org.techhouse.simplejs.internal.temporal.TemporalRounding.digitsForUnit;
import static org.techhouse.simplejs.internal.temporal.TemporalRounding.requireSecondOrSmallerUnit;
import static org.techhouse.simplejs.internal.temporal.TemporalRounding.roundNonNegative;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_DAY;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.Disambiguation;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TimeZoneStringParser;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class PlainDateTimeConversions {
    public static JsValue round(JsTemporalPlainDateTime receiver, JsValue roundToArg, InterpreterOps ops) {
        if (roundToArg == null || roundToArg instanceof JsUndefined) {
            throw new TypeErrorException("round() requires an options parameter");
        }
        final var options = roundToArg instanceof JsString unit ? smallestUnitOptions(unit) : roundToArg;
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a string");
        }
        final var increment = readIncrementOption(options, ops);
        final var mode = readRoundingModeOption(options, ops, RoundingMode.HALF_EXPAND);
        final var smallestUnitValue = optionOrUndefined(options, "smallestUnit", ops);
        final var smallestUnitRaw = smallestUnitValue instanceof JsUndefined
                ? null
                : JsCoercion.toStr(smallestUnitValue, ops);
        if (smallestUnitRaw == null) {
            throw new RangeErrorException("smallestUnit is required");
        }
        final var smallestUnit = Unit.parseTemporalUnit(smallestUnitRaw);
        if (smallestUnit.isLargerThan(Unit.DAY)) {
            throw new RangeErrorException(
                    "Invalid smallestUnit for Temporal.PlainDateTime.prototype.round: " + smallestUnit.singular());
        }
        validateRoundingIncrement(increment, smallestUnit);
        return roundToUnit(receiver, smallestUnit, increment, mode);
    }

    public static JsTemporalPlainDateTime roundByIncrementNanos(JsTemporalPlainDateTime receiver,
            BigInteger incrementNanos, RoundingMode mode) {
        final var nanosOfDay = toNanosOfDay(receiver.time());
        final var rounded = roundNonNegative(nanosOfDay, incrementNanos, mode);
        final var dayCarry = rounded.divide(NANOS_PER_DAY);
        final var remainder = rounded.subtract(dayCarry.multiply(NANOS_PER_DAY));
        final var newDate = dayCarry.signum() == 0
                ? receiver.date()
                : IsoCalendar.addDate(receiver.date(), 0, 0, 0, dayCarry.doubleValue(), RegulateOverflow.CONSTRAIN);
        return dateTime(newDate, fromNanosOfDay(remainder.longValueExact()));
    }

    public static JsTemporalPlainDateTime roundToUnit(JsTemporalPlainDateTime receiver, Unit unit, long increment,
            RoundingMode mode) {
        final var perUnit = unit == Unit.DAY ? NANOS_PER_DAY : nanosPerUnit(unit);
        return roundByIncrementNanos(receiver, perUnit.multiply(BigInteger.valueOf(increment)), mode);
    }

    public static JsTemporalPlainDateTime roundToFractionalDigits(JsTemporalPlainDateTime receiver, int digits,
            RoundingMode mode) {
        return roundByIncrementNanos(receiver, BigInteger.TEN.pow(9 - digits), mode);
    }

    public static JsValue toPlainDate(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainDate(receiver.date());
    }

    public static JsValue toPlainTime(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainTime(receiver.time());
    }

    public static JsValue toPlainYearMonth(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainYearMonth(new Iso8601Fields(receiver.year(), receiver.month(), 1));
    }

    public static JsValue toPlainMonthDay(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainMonthDay(new Iso8601Fields(JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR,
                receiver.month(), receiver.day()));
    }

    public static JsValue toZonedDateTime(JsTemporalPlainDateTime receiver, JsValue timeZoneArg, JsValue optionsArg,
            InterpreterOps ops) {
        final var id = extractTimeZoneId(timeZoneArg, ops);
        final var disambiguation = readDisambiguationOption(optionsArg, ops);
        final var zone = ZonedDateTimeZones.zoneOf(id);
        final var date = receiver.date();
        final var time = receiver.time();
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final ZonedDateTime zdt;
        try {
            final var local = LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(),
                    time.second(), nanoOfSecond);
            zdt = resolveLocalToZoned(local, zone, disambiguation);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid Temporal.PlainDateTime for toZonedDateTime: " + e.getMessage());
        }
        final var epochNanos = BigInteger.valueOf(zdt.toEpochSecond()).multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(zdt.getNano()));
        JsTemporalInstant.fromEpochNanoseconds(epochNanos);
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(zdt, id);
    }

    public static ZonedDateTime resolveLocalToZoned(LocalDateTime local, ZoneId zone, Disambiguation disambiguation) {
        final var transition = zone.getRules().getTransition(local);
        if (transition == null) {
            return ZonedDateTime.of(local, zone);
        }
        if (transition.isGap()) {
            return switch (disambiguation) {
                case REJECT -> throw new RangeErrorException(
                        "Temporal.PlainDateTime.prototype.toZonedDateTime: local time falls in a time zone "
                                + "transition gap and disambiguation is 'reject'");
                case EARLIER -> local.toInstant(transition.getOffsetAfter()).atZone(zone);
                case LATER, COMPATIBLE -> local.toInstant(transition.getOffsetBefore()).atZone(zone);
            };
        }
        return switch (disambiguation) {
            case REJECT -> throw new RangeErrorException(
                    "Temporal.PlainDateTime.prototype.toZonedDateTime: local time is ambiguous (time zone "
                            + "transition fold) and disambiguation is 'reject'");
            case LATER -> local.toInstant(transition.getOffsetAfter()).atZone(zone);
            case EARLIER, COMPATIBLE -> local.toInstant(transition.getOffsetBefore()).atZone(zone);
        };
    }

    public static String extractTimeZoneId(JsValue options, InterpreterOps ops) {
        String raw = null;
        if (options instanceof JsString s) {
            raw = TimeZoneStringParser.parseTimeZoneIdentifierFlexible(s.getValue());
        } else if (options instanceof JsObject obj) {
            final var timeZone = ops.getMember(obj, new JsString("timeZone"));
            if (timeZone instanceof JsString s) {
                raw = TimeZoneStringParser.parseTimeZoneIdentifierFlexible(s.getValue());
            }
        }
        if (raw == null) {
            throw new TypeErrorException("Temporal.PlainDateTime.prototype.toZonedDateTime requires a timeZone");
        }
        return TemporalCalendarIdentifier.asciiEqualsIgnoreCase(raw, "utc") ? "UTC" : raw;
    }

    public static JsValue toStringMethod(JsTemporalPlainDateTime receiver, JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg == null || optionsArg instanceof JsUndefined) {
            return new JsString(receiver.toString());
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        final var calendarName = readCalendarNameOption(optionsArg, ops);
        final var fsdValue = optionOrUndefined(optionsArg, "fractionalSecondDigits", ops);
        Integer digits = null;
        if (!(fsdValue instanceof JsUndefined)) {
            if (fsdValue instanceof JsNumber) {
                final var numeric = JsCoercion.toNumber(fsdValue, ops);
                if (Double.isNaN(numeric)) {
                    throw new RangeErrorException("fractionalSecondDigits must not be NaN");
                }
                final var floored = (int) Math.floor(numeric);
                if (floored < 0 || floored > 9) {
                    throw new RangeErrorException("fractionalSecondDigits must be 0..9 or \"auto\", got " + floored);
                }
                digits = floored;
            } else if (!"auto".equals(JsCoercion.toStr(fsdValue, ops))) {
                throw new RangeErrorException("fractionalSecondDigits must be 0..9 or \"auto\"");
            }
        }
        final var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        final var smallestUnitValue = optionOrUndefined(optionsArg, "smallestUnit", ops);
        Unit smallestUnit = null;
        if (!(smallestUnitValue instanceof JsUndefined)) {
            smallestUnit = Unit.parseTemporalUnit(JsCoercion.toStr(smallestUnitValue, ops));
        }
        if (smallestUnit != null) {
            if (smallestUnit == Unit.MINUTE) {
                final var rounded = roundToUnit(receiver, Unit.MINUTE, 1, mode);
                return new JsString(TemporalFormatter.formatDate(rounded.date()) + "T" + pad2(rounded.time().hour())
                        + ":" + pad2(rounded.time().minute())
                        + TemporalFormatter.formatCalendarAnnotation(calendarName));
            }
            requireSecondOrSmallerUnit(smallestUnit);
            final var rounded = roundToUnit(receiver, smallestUnit, 1, mode);
            return new JsString(TemporalFormatter.formatDateTime(rounded.date(), rounded.time(),
                    digitsForUnit(smallestUnit), calendarName));
        }
        if (digits != null) {
            final var rounded = roundToFractionalDigits(receiver, digits, mode);
            return new JsString(TemporalFormatter.formatDateTime(rounded.date(), rounded.time(), digits, calendarName));
        }
        return new JsString(TemporalFormatter.formatDateTime(receiver.date(), receiver.time(), null, calendarName));
    }

    public static JsValue getISOFields(JsTemporalPlainDateTime receiver) {
        final var obj = new JsObject();
        obj.set("calendar", new JsString("iso8601"));
        obj.set("isoDay", new JsNumber(receiver.day()));
        obj.set("isoMonth", new JsNumber(receiver.month()));
        obj.set("isoYear", new JsNumber(receiver.year()));
        final var t = receiver.time();
        obj.set("isoHour", new JsNumber(t.hour()));
        obj.set("isoMinute", new JsNumber(t.minute()));
        obj.set("isoSecond", new JsNumber(t.second()));
        obj.set("isoMillisecond", new JsNumber(t.millisecond()));
        obj.set("isoMicrosecond", new JsNumber(t.microsecond()));
        obj.set("isoNanosecond", new JsNumber(t.nanosecond()));
        return obj;
    }

    private PlainDateTimeConversions() {
    }
}

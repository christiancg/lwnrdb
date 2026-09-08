package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.optionOrUndefined;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readIncrementOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readRoundingModeOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.smallestUnitOptions;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.validateRoundingIncrement;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.epochNanosOf;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.resolveToZoned;
import static org.techhouse.simplejs.internal.temporal.TemporalRounding.roundNonNegative;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_DAY;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_HOUR;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MICRO;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MILLI;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MINUTE;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND;

import java.math.BigInteger;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.Disambiguation;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ZonedDateTimeRounding {
    public static JsValue round(JsTemporalZonedDateTime receiver, JsValue roundToArg, InterpreterOps ops) {
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
                    "Invalid smallestUnit for Temporal.ZonedDateTime.prototype.round: " + smallestUnit.singular());
        }
        validateRoundingIncrement(increment, smallestUnit);
        if (smallestUnit == Unit.DAY) {
            return roundToCalendarDay(receiver, mode);
        }
        return roundToLocalUnit(receiver, smallestUnit, increment, mode);
    }

    public static JsValue roundToCalendarDay(JsTemporalZonedDateTime receiver, RoundingMode mode) {
        final var zdt = receiver.toJavaZonedDateTime();
        final var startOfDay = zdt.toLocalDate().atStartOfDay(receiver.zone());
        final var startOfNextDay = zdt.toLocalDate().plusDays(1).atStartOfDay(receiver.zone());
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(startOfDay));
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(startOfNextDay));
        final var dayLengthNanos = epochNanosOf(startOfNextDay).subtract(epochNanosOf(startOfDay));
        final var offsetIntoDayNanos = epochNanosOf(zdt).subtract(epochNanosOf(startOfDay));
        final var rounded = roundNonNegative(offsetIntoDayNanos, dayLengthNanos, mode);
        final var resultZdt = rounded.signum() == 0 ? startOfDay : startOfNextDay;
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(resultZdt, receiver.timeZoneId());
    }

    public static BigInteger nanosPerUnit(Unit unit) {
        return switch (unit) {
            case DAY -> NANOS_PER_DAY;
            case HOUR -> NANOS_PER_HOUR;
            case MINUTE -> NANOS_PER_MINUTE;
            case SECOND -> NANOS_PER_SECOND;
            case MILLISECOND -> NANOS_PER_MILLI;
            case MICROSECOND -> NANOS_PER_MICRO;
            case NANOSECOND -> BigInteger.ONE;
            default -> throw new RangeErrorException("Unsupported unit for Temporal.ZonedDateTime: " + unit.singular());
        };
    }

    public static long toNanosOfDayLong(IsoTimeFields t) {
        return t.hour() * 3_600_000_000_000L + t.minute() * 60_000_000_000L + t.second() * 1_000_000_000L
                + t.millisecond() * 1_000_000L + t.microsecond() * 1_000L + t.nanosecond();
    }

    public static IsoTimeFields fromNanosOfDay(long nanos) {
        var remaining = nanos;
        final var hour = (int) (remaining / 3_600_000_000_000L);
        remaining %= 3_600_000_000_000L;
        final var minute = (int) (remaining / 60_000_000_000L);
        remaining %= 60_000_000_000L;
        final var second = (int) (remaining / 1_000_000_000L);
        remaining %= 1_000_000_000L;
        final var millisecond = (int) (remaining / 1_000_000L);
        remaining %= 1_000_000L;
        final var microsecond = (int) (remaining / 1_000L);
        final var nanosecond = (int) (remaining % 1_000L);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    public static JsTemporalZonedDateTime roundLocalByIncrementNanos(JsTemporalZonedDateTime receiver,
            BigInteger incrementNanos, RoundingMode mode) {
        final var fields = receiver.isoFieldsAtLocal();
        final var nanosOfDay = BigInteger.valueOf(toNanosOfDayLong(fields.time()));
        final var rounded = roundNonNegative(nanosOfDay, incrementNanos, mode);
        final var dayCarry = rounded.divide(NANOS_PER_DAY);
        final var remainder = rounded.subtract(dayCarry.multiply(NANOS_PER_DAY));
        final var newDate = dayCarry.signum() == 0
                ? fields.date()
                : IsoCalendar.addDate(fields.date(), 0, 0, 0, dayCarry.doubleValue(), RegulateOverflow.CONSTRAIN);
        final var newTime = fromNanosOfDay(remainder.longValueExact());
        return resolveToZoned(newDate, newTime, receiver.zone(), receiver.timeZoneId(), Disambiguation.COMPATIBLE);
    }

    public static JsTemporalZonedDateTime roundToLocalUnit(JsTemporalZonedDateTime receiver, Unit unit, long increment,
            RoundingMode mode) {
        return roundLocalByIncrementNanos(receiver, nanosPerUnit(unit).multiply(BigInteger.valueOf(increment)), mode);
    }

    public static JsTemporalZonedDateTime roundToLocalFractionalDigits(JsTemporalZonedDateTime receiver, int digits,
            RoundingMode mode) {
        return roundLocalByIncrementNanos(receiver, BigInteger.TEN.pow(9 - digits), mode);
    }

    private ZonedDateTimeRounding() {
    }
}

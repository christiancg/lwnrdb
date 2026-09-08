package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toDurationFields;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toDurationNanos;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.negateRoundingMode;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.optionOrUndefined;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readIncrementOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readOverflowOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readRoundingModeOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readSmallestUnitOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.validateRoundingIncrementForDuration;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeFrom.toZonedDateTime;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.epochNanosOf;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.resolveToZonedDateTime;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RelativeDurationMath;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ZonedDateTimeArithmetic {
    public static DurationFields negate(DurationFields d) {
        return DurationMath.negate(d);
    }

    public static JsValue add(JsTemporalZonedDateTime receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        return addZonedDateTime(receiver, toDurationFields(durationLike, ops), readOverflowOption(optionsArg, ops));
    }

    public static JsValue subtract(JsTemporalZonedDateTime receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        return addZonedDateTime(receiver, negate(toDurationFields(durationLike, ops)),
                readOverflowOption(optionsArg, ops));
    }

    public static JsValue addZonedDateTime(JsTemporalZonedDateTime receiver, DurationFields duration,
            RegulateOverflow overflow) {
        final var fields = receiver.isoFieldsAtLocal();
        final ZonedDateTime intermediate;
        if (duration.years() != 0 || duration.months() != 0 || duration.weeks() != 0 || duration.days() != 0) {
            final var newDate = IsoCalendar.addDate(fields.date(), duration.years(), duration.months(),
                    duration.weeks(), duration.days(), overflow);
            intermediate = resolveToZonedDateTime(newDate, fields.time(), receiver.zone());
        } else {
            intermediate = receiver.toJavaZonedDateTime();
        }
        final var deltaNanos = toDurationNanos(duration);
        final var resultNanos = epochNanosOf(intermediate).add(deltaNanos);
        return JsTemporalZonedDateTime.fromEpochNanoseconds(resultNanos, receiver.zone(), receiver.timeZoneId());
    }

    public static JsValue until(JsTemporalZonedDateTime receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, false, ops);
    }

    public static JsValue since(JsTemporalZonedDateTime receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, true, ops);
    }

    public static JsValue difference(JsTemporalZonedDateTime receiver, JsValue otherArg, JsValue optionsArg,
            boolean isSince, InterpreterOps ops) {
        final var other = toZonedDateTime(otherArg, ops);
        final var largestUnitValue = optionOrUndefined(optionsArg, "largestUnit", ops);
        final var largestUnitRaw = largestUnitValue instanceof JsUndefined
                ? null
                : JsCoercion.toStr(largestUnitValue, ops);
        final var increment = readIncrementOption(optionsArg, ops);
        var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        final var smallestUnit = readSmallestUnitOption(optionsArg, ops);
        final var largestUnitDefault = smallestUnit.isLargerThan(Unit.HOUR) ? smallestUnit : Unit.HOUR;
        final var largestUnit = largestUnitRaw == null || "auto".equals(largestUnitRaw)
                ? largestUnitDefault
                : Unit.parseTemporalUnit(largestUnitRaw);
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        if (isSince) {
            mode = negateRoundingMode(mode);
        }
        DurationFields fields;
        if (largestUnit.isLargerThan(Unit.DAY)) {
            final var anchorFields = receiver.isoFieldsAtLocal();
            final var anchor = RelativeDurationMath.Anchor.zoned(anchorFields.date(), anchorFields.time(),
                    receiver.zone());
            final var otherLocal = other.isoFieldsAtLocal();
            fields = smallestUnit != Unit.NANOSECOND || increment != 1
                    ? RelativeDurationMath.roundedDifference(anchor, otherLocal.date(), otherLocal.time(), largestUnit,
                            smallestUnit, increment, mode)
                    : DurationMath.differenceCalendar(anchor.date(), anchor.time(), otherLocal.date(),
                            otherLocal.time(), largestUnit);
        } else {
            fields = largestUnit == Unit.DAY
                    ? dayAndTimeDifference(receiver, other)
                    : DurationMath.balanceFromTotalNanoseconds(
                            other.epochNanoseconds().subtract(receiver.epochNanoseconds()), largestUnit);
            if (smallestUnit != Unit.NANOSECOND || increment != 1) {
                validateRoundingIncrementForDuration(increment, smallestUnit);
                if (smallestUnit == Unit.DAY) {
                    validateCalendarUnitRoundingBound(receiver, other, increment, isSince);
                }
                fields = DurationMath.roundDuration(fields, smallestUnit, increment, mode, largestUnit);
            }
        }
        if (isSince) {
            fields = negate(fields);
        }
        return new JsTemporalDuration(fields);
    }

    public static void validateCalendarUnitRoundingBound(JsTemporalZonedDateTime receiver,
            JsTemporalZonedDateTime other, long increment, boolean isSince) {
        final var rawSign = other.epochNanoseconds().compareTo(receiver.epochNanoseconds());
        if (rawSign == 0) {
            return;
        }
        final var finalSign = isSince ? -Integer.signum(rawSign) : Integer.signum(rawSign);
        final var anchorDate = receiver.isoFieldsAtLocal().date();
        final var endDate = IsoCalendar.addDate(anchorDate, 0, 0, 0, (double) finalSign * increment,
                RegulateOverflow.CONSTRAIN);
        final var midnight = new IsoTimeFields(0, 0, 0, 0, 0, 0);
        final var endZdt = resolveToZonedDateTime(endDate, midnight, receiver.zone());
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(endZdt));
    }

    public static DurationFields dayAndTimeDifference(JsTemporalZonedDateTime receiver, JsTemporalZonedDateTime other) {
        final var startZdt = receiver.toJavaZonedDateTime();
        final var endZdt = other.toJavaInstant().atZone(receiver.zone());
        final var days = startZdt.until(endZdt, ChronoUnit.DAYS);
        final var intermediate = startZdt.plusDays(days);
        final var remainderNanos = other.epochNanoseconds().subtract(epochNanosOf(intermediate));
        final var timeFields = DurationMath.balanceFromTotalNanoseconds(remainderNanos, Unit.HOUR);
        return new DurationFields(0, 0, 0, (double) days, timeFields.hours(), timeFields.minutes(),
                timeFields.seconds(), timeFields.milliseconds(), timeFields.microseconds(), timeFields.nanoseconds());
    }

    public static JsValue equalsMethod(JsTemporalZonedDateTime receiver, JsValue otherArg, InterpreterOps ops) {
        return JsBoolean.of(receiver.isEqualTo(toZonedDateTime(otherArg, ops)));
    }

    public static int compare(JsTemporalZonedDateTime a, JsTemporalZonedDateTime b) {
        return Integer.signum(a.compareEpoch(b));
    }

    private ZonedDateTimeArithmetic() {
    }
}

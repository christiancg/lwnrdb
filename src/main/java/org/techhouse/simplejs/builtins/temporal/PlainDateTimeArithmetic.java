package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.TemporalPlainDateTimeBuiltins.dateTime;
import static org.techhouse.simplejs.builtins.TemporalPlainDateTimeBuiltins.toDateTime;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toDurationFields;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toDurationNanos;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.negateRoundingMode;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.optionOrUndefined;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readIncrementOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readOverflowOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readRoundingModeOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readSmallestUnitOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.validateRoundingIncrementForDuration;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.toNanosOfDay;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_DAY;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_HOUR_LONG;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MICRO_LONG;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MILLI_LONG;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MINUTE_LONG;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND_LONG;

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
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class PlainDateTimeArithmetic {
    public static DurationFields negate(DurationFields d) {
        return DurationMath.negate(d);
    }

    public static JsValue add(JsTemporalPlainDateTime receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        return addDateTime(receiver, toDurationFields(durationLike, ops), readOverflowOption(optionsArg, ops));
    }

    public static JsValue subtract(JsTemporalPlainDateTime receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        return addDateTime(receiver, negate(toDurationFields(durationLike, ops)), readOverflowOption(optionsArg, ops));
    }

    public static JsValue addDateTime(JsTemporalPlainDateTime receiver, DurationFields duration,
            RegulateOverflow overflow) {
        final var nanosOfDay = toNanosOfDay(receiver.time());
        final var deltaTimeNanos = toDurationNanos(duration);
        final var total = nanosOfDay.add(deltaTimeNanos);
        final var dayCarry = total.subtract(total.mod(NANOS_PER_DAY)).divide(NANOS_PER_DAY);
        final var newNanosOfDay = total.subtract(dayCarry.multiply(NANOS_PER_DAY));
        final var newDate = IsoCalendar.addDate(receiver.date(), duration.years(), duration.months(), duration.weeks(),
                duration.days() + dayCarry.doubleValue(), overflow);
        return dateTime(newDate, fromNanosOfDay(newNanosOfDay.longValueExact()));
    }

    public static IsoTimeFields fromNanosOfDay(long nanos) {
        var remaining = nanos;
        final var hour = (int) (remaining / NANOS_PER_HOUR_LONG);
        remaining %= NANOS_PER_HOUR_LONG;
        final var minute = (int) (remaining / NANOS_PER_MINUTE_LONG);
        remaining %= NANOS_PER_MINUTE_LONG;
        final var second = (int) (remaining / NANOS_PER_SECOND_LONG);
        remaining %= NANOS_PER_SECOND_LONG;
        final var millisecond = (int) (remaining / NANOS_PER_MILLI_LONG);
        remaining %= NANOS_PER_MILLI_LONG;
        final var microsecond = (int) (remaining / NANOS_PER_MICRO_LONG);
        final var nanosecond = (int) (remaining % NANOS_PER_MICRO_LONG);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    public static JsValue until(JsTemporalPlainDateTime receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, false, ops);
    }

    public static JsValue since(JsTemporalPlainDateTime receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, true, ops);
    }

    public static JsValue difference(JsTemporalPlainDateTime receiver, JsValue otherArg, JsValue optionsArg,
            boolean isSince, InterpreterOps ops) {
        final var other = toDateTime(otherArg, ops);
        final var largestUnitValue = optionOrUndefined(optionsArg, "largestUnit", ops);
        final var largestUnitRaw = largestUnitValue instanceof JsUndefined
                ? null
                : JsCoercion.toStr(largestUnitValue, ops);
        final var increment = readIncrementOption(optionsArg, ops);
        var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        final var smallestUnit = readSmallestUnitOption(optionsArg, ops);
        final var largestUnitDefault = smallestUnit.isLargerThan(Unit.DAY) ? smallestUnit : Unit.DAY;
        final var largestUnit = largestUnitRaw == null || "auto".equals(largestUnitRaw)
                ? largestUnitDefault
                : Unit.parseTemporalUnit(largestUnitRaw);
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        if (isSince) {
            mode = negateRoundingMode(mode);
        }
        var fields = DurationMath.differenceCalendar(receiver.date(), receiver.time(), other.date(), other.time(),
                largestUnit);
        if (smallestUnit != Unit.NANOSECOND || increment != 1) {
            if (largestUnit.isLargerThan(Unit.DAY)) {
                final var anchor = RelativeDurationMath.Anchor.plain(receiver.date(), receiver.time());
                fields = RelativeDurationMath.roundedDifference(anchor, other.date(), other.time(), largestUnit,
                        smallestUnit, increment, mode);
            } else {
                validateRoundingIncrementForDuration(increment, smallestUnit);
                fields = DurationMath.roundDuration(fields, smallestUnit, increment, mode, largestUnit);
            }
        }
        if (isSince) {
            fields = negate(fields);
        }
        return new JsTemporalDuration(fields);
    }

    private PlainDateTimeArithmetic() {
    }
}

package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.DbTimeBuiltins.member;
import static org.techhouse.simplejs.builtins.TemporalPlainTimeBuiltins.fromNanosOfDay;
import static org.techhouse.simplejs.builtins.TemporalPlainTimeBuiltins.toIntegerWithTruncation;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeFrom.toTemporalTime;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeRounding.validateRoundingIncrement;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.orZeroDuration;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.readDurationField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toDurationNanos;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.negateRoundingMode;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.toNanosOfDay;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.DURATION_DATE_LIMIT;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.DURATION_TIME_LIMIT;
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
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.DurationStringParser;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class PlainTimeDifference {
    public static void requireTimeUnit(Unit unit) {
        if (unit.isLargerThan(Unit.HOUR)) {
            throw new RangeErrorException("unit must be hour, minute, second, millisecond, microsecond, or nanosecond");
        }
    }

    public static JsValue addOrSubtract(JsTemporalPlainTime receiver, JsValue durationLike, int sign,
            InterpreterOps ops) {
        final var duration = toDurationFields(durationLike, ops);
        final var durationFields = new DurationFields(0, 0, 0, 0, sign * duration.hours(), sign * duration.minutes(),
                sign * duration.seconds(), sign * duration.milliseconds(), sign * duration.microseconds(),
                sign * duration.nanoseconds());
        final var total = toNanosOfDay(receiver.getFields()).add(toDurationNanos(durationFields)).mod(NANOS_PER_DAY);
        return new JsTemporalPlainTime(fromNanosOfDay(total.longValueExact()));
    }

    public static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsString s) {
            final var parsed = DurationStringParser.parseDuration(s.getValue());
            requireValidDuration(parsed);
            return parsed;
        }
        if (!InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("Invalid Temporal.Duration-like value");
        }
        final var days = readDurationField(value, "days", ops);
        final var hours = readDurationField(value, "hours", ops);
        final var microseconds = readDurationField(value, "microseconds", ops);
        final var milliseconds = readDurationField(value, "milliseconds", ops);
        final var minutes = readDurationField(value, "minutes", ops);
        final var months = readDurationField(value, "months", ops);
        final var nanoseconds = readDurationField(value, "nanoseconds", ops);
        final var seconds = readDurationField(value, "seconds", ops);
        final var weeks = readDurationField(value, "weeks", ops);
        final var years = readDurationField(value, "years", ops);
        if (years == null && months == null && weeks == null && days == null && hours == null && minutes == null
                && seconds == null && milliseconds == null && microseconds == null && nanoseconds == null) {
            throw new TypeErrorException("Duration-like object must contain at least one recognized property");
        }
        final var fields = new DurationFields(orZeroDuration(years), orZeroDuration(months), orZeroDuration(weeks),
                orZeroDuration(days), orZeroDuration(hours), orZeroDuration(minutes), orZeroDuration(seconds),
                orZeroDuration(milliseconds), orZeroDuration(microseconds), orZeroDuration(nanoseconds));
        DurationMath.sign(fields);
        requireValidDuration(fields);
        return fields;
    }

    public static void requireValidDuration(DurationFields f) {
        for (final var component : new double[]{f.years(), f.months(), f.weeks(), f.days(), f.hours(), f.minutes(),
                f.seconds(), f.milliseconds(), f.microseconds(), f.nanoseconds()}) {
            if (Double.isNaN(component) || Double.isInfinite(component)) {
                throw new RangeErrorException("Duration component must be a finite number");
            }
        }
        if (Math.abs(f.years()) >= DURATION_DATE_LIMIT || Math.abs(f.months()) >= DURATION_DATE_LIMIT
                || Math.abs(f.weeks()) >= DURATION_DATE_LIMIT) {
            throw new RangeErrorException("Duration years/months/weeks component is out of range");
        }
        final var totalNanos = BigInteger.valueOf((long) f.days()).multiply(NANOS_PER_DAY)
                .add(BigInteger.valueOf((long) f.hours()).multiply(NANOS_PER_HOUR))
                .add(BigInteger.valueOf((long) f.minutes()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf((long) f.seconds()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf((long) f.milliseconds()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf((long) f.microseconds()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf((long) f.nanoseconds()));
        if (totalNanos.abs().compareTo(DURATION_TIME_LIMIT) >= 0) {
            throw new RangeErrorException("Duration time component is out of range");
        }
    }

    public static JsValue difference(JsTemporalPlainTime receiver, JsValue otherArg, JsValue optionsArg,
            boolean isSince, InterpreterOps ops) {
        final var other = toTemporalTime(otherArg, ops);
        final var deltaNanos = toNanosOfDay(other.getFields()).subtract(toNanosOfDay(receiver.getFields()));
        var largestUnit = Unit.HOUR;
        var smallestUnit = Unit.NANOSECOND;
        var increment = 1L;
        var mode = RoundingMode.TRUNC;
        if (!(optionsArg instanceof JsUndefined)) {
            if (!InterpreterUtils.isObjectLike(optionsArg)) {
                throw new TypeErrorException("options must be an object");
            }
            String largestUnitStr = null;
            final var largestUnitValue = member(optionsArg, "largestUnit", ops);
            if (!(largestUnitValue instanceof JsUndefined)) {
                largestUnitStr = JsCoercion.toStr(largestUnitValue, ops);
            }
            Long incrementRaw = null;
            final var incrementValue = member(optionsArg, "roundingIncrement", ops);
            if (!(incrementValue instanceof JsUndefined)) {
                incrementRaw = (long) toIntegerWithTruncation(incrementValue, ops);
            }
            String modeStr = null;
            final var modeValue = member(optionsArg, "roundingMode", ops);
            if (!(modeValue instanceof JsUndefined)) {
                modeStr = JsCoercion.toStr(modeValue, ops);
            }
            String smallestUnitStr = null;
            final var smallestUnitValue = member(optionsArg, "smallestUnit", ops);
            if (!(smallestUnitValue instanceof JsUndefined)) {
                smallestUnitStr = JsCoercion.toStr(smallestUnitValue, ops);
            }
            if (largestUnitStr != null) {
                largestUnit = "auto".equals(largestUnitStr) ? Unit.HOUR : Unit.parseTemporalUnit(largestUnitStr);
                requireTimeUnit(largestUnit);
            }
            if (smallestUnitStr != null) {
                smallestUnit = Unit.parseTemporalUnit(smallestUnitStr);
                requireTimeUnit(smallestUnit);
            }
            if (incrementRaw != null) {
                if (incrementRaw < 1 || incrementRaw > 1_000_000_000) {
                    throw new RangeErrorException("roundingIncrement out of range: " + incrementRaw);
                }
                increment = incrementRaw;
            }
            validateRoundingIncrement(increment, smallestUnit);
            mode = modeStr == null ? RoundingMode.TRUNC : RoundingMode.parse(modeStr);
            if (isSince) {
                mode = negateRoundingMode(mode);
            }
        }
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        var fields = new DurationFields(0, 0, 0, 0, 0, 0, 0, 0, 0, deltaNanos.doubleValue());
        fields = DurationMath.roundDuration(fields, smallestUnit, increment, mode, largestUnit);
        if (isSince) {
            fields = negate(fields);
        }
        return new JsTemporalDuration(fields);
    }

    public static DurationFields negate(DurationFields f) {
        return DurationMath.negate(f);
    }

    private PlainTimeDifference() {
    }
}

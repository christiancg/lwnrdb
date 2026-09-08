package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.TemporalDurationBuiltins.RELATIVE_TO_REQUIRED;
import static org.techhouse.simplejs.builtins.TemporalDurationBuiltins.hasCalendarUnits;
import static org.techhouse.simplejs.builtins.TemporalDurationBuiltins.isAbsent;
import static org.techhouse.simplejs.builtins.temporal.DurationRelativeTo.roundingIncrementValue;
import static org.techhouse.simplejs.builtins.temporal.DurationRelativeTo.toRelativeToAnchor;

import java.math.BigDecimal;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RelativeDurationMath;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class DurationRounding {
    public static JsValue round(JsTemporalDuration receiver, JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg == null || optionsArg instanceof JsUndefined) {
            throw new TypeErrorException("Temporal.Duration.prototype.round requires an options argument");
        }
        final var options = optionsArg instanceof JsString unit ? singleKeyOptions("smallestUnit", unit) : optionsArg;
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a unit string");
        }
        final var largestUnitValue = ops.getMember(options, new JsString("largestUnit"));
        final var largestUnitRaw = isAbsent(largestUnitValue) ? null : JsCoercion.toStr(largestUnitValue, ops);
        final var largestUnitParsed = largestUnitRaw == null || "auto".equals(largestUnitRaw)
                ? null
                : Unit.parseTemporalUnit(largestUnitRaw);
        final var relativeToValue = ops.getMember(options, new JsString("relativeTo"));
        final var anchor = isAbsent(relativeToValue) ? null : toRelativeToAnchor(relativeToValue, ops);
        final var incrementValue = ops.getMember(options, new JsString("roundingIncrement"));
        final var increment = isAbsent(incrementValue) ? 1 : roundingIncrementValue(incrementValue, ops);
        final var modeValue = ops.getMember(options, new JsString("roundingMode"));
        final var mode = isAbsent(modeValue)
                ? RoundingMode.HALF_EXPAND
                : RoundingMode.parse(JsCoercion.toStr(modeValue, ops));
        final var smallestUnitValue = ops.getMember(options, new JsString("smallestUnit"));
        final var smallestUnitRaw = isAbsent(smallestUnitValue) ? null : JsCoercion.toStr(smallestUnitValue, ops);

        if (smallestUnitRaw == null && largestUnitRaw == null) {
            throw new RangeErrorException("round requires at least one of smallestUnit or largestUnit");
        }
        final var fields = receiver.getFields();
        final var smallestUnit = smallestUnitRaw == null ? Unit.NANOSECOND : Unit.parseTemporalUnit(smallestUnitRaw);
        final var largestUnit = largestUnitParsed != null
                ? largestUnitParsed
                : coarserOf(defaultLargestUnit(fields), smallestUnit);
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        if (increment < 1 || increment > 1_000_000_000) {
            throw new RangeErrorException("roundingIncrement out of range: " + increment);
        }
        if (anchor != null) {
            validateIncrementForBalancing(smallestUnit, largestUnit, increment);
            validateRoundingIncrementForUnit(increment, smallestUnit);
            final var endPoint = RelativeDurationMath.applyDuration(anchor, fields, RegulateOverflow.CONSTRAIN);
            RelativeDurationMath.toEpochNanos(anchor, endPoint.date(), endPoint.time());
            final var result = smallestUnit == Unit.NANOSECOND && increment == 1
                    ? DurationMath.differenceCalendar(anchor.date(), anchor.time(), endPoint.date(), endPoint.time(),
                            largestUnit)
                    : RelativeDurationMath.roundedDifference(anchor, endPoint.date(), endPoint.time(), largestUnit,
                            smallestUnit, increment, mode);
            return new JsTemporalDuration(result);
        }
        if (hasCalendarUnits(fields) || largestUnit.isLargerThan(Unit.DAY) || smallestUnit.isLargerThan(Unit.DAY)) {
            throw new RangeErrorException(RELATIVE_TO_REQUIRED);
        }
        return new JsTemporalDuration(DurationMath.roundDuration(fields, smallestUnit, increment, mode, largestUnit));
    }

    public static Unit defaultLargestUnit(DurationFields f) {
        if (f.years() != 0) {
            return Unit.YEAR;
        }
        if (f.months() != 0) {
            return Unit.MONTH;
        }
        if (f.weeks() != 0) {
            return Unit.WEEK;
        }
        if (f.days() != 0) {
            return Unit.DAY;
        }
        if (f.hours() != 0) {
            return Unit.HOUR;
        }
        if (f.minutes() != 0) {
            return Unit.MINUTE;
        }
        if (f.seconds() != 0) {
            return Unit.SECOND;
        }
        if (f.milliseconds() != 0) {
            return Unit.MILLISECOND;
        }
        if (f.microseconds() != 0) {
            return Unit.MICROSECOND;
        }
        return Unit.NANOSECOND;
    }

    public static Unit coarserOf(Unit a, Unit b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    public static void validateIncrementForBalancing(Unit smallestUnit, Unit largestUnit, long increment) {
        if (smallestUnit.ordinal() <= Unit.DAY.ordinal() && increment != 1 && largestUnit != smallestUnit) {
            throw new RangeErrorException("roundingIncrement > 1 is not supported for smallestUnit \""
                    + smallestUnit.singular() + "\" when balancing to a different largestUnit");
        }
    }

    public static void validateRoundingIncrementForUnit(long increment, Unit unit) {
        if (unit.ordinal() <= Unit.DAY.ordinal()) {
            return;
        }
        final var maximum = switch (unit) {
            case HOUR -> 24;
            case MINUTE, SECOND -> 60;
            case MILLISECOND, MICROSECOND, NANOSECOND -> 1000;
            default -> throw new RangeErrorException("Invalid unit for rounding: " + unit);
        };
        if (maximum % increment != 0 || increment == maximum) {
            throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit " + unit.singular());
        }
    }

    public static JsValue total(JsTemporalDuration receiver, JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg == null || optionsArg instanceof JsUndefined) {
            throw new TypeErrorException("Temporal.Duration.prototype.total requires an options argument");
        }
        final var options = optionsArg instanceof JsString unit ? singleKeyOptions("unit", unit) : optionsArg;
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a unit string");
        }
        final var relativeToValue = ops.getMember(options, new JsString("relativeTo"));
        final var anchor = isAbsent(relativeToValue) ? null : toRelativeToAnchor(relativeToValue, ops);
        final var unitValue = ops.getMember(options, new JsString("unit"));
        if (isAbsent(unitValue)) {
            throw new RangeErrorException("total requires a unit option");
        }
        final var unit = Unit.parseTemporalUnit(JsCoercion.toStr(unitValue, ops));
        final var fields = receiver.getFields();
        if (anchor != null) {
            final var endPoint = RelativeDurationMath.applyDuration(anchor, fields, RegulateOverflow.CONSTRAIN);
            return new JsNumber(RelativeDurationMath.totalInUnit(anchor, endPoint.date(), endPoint.time(), unit));
        }
        if (hasCalendarUnits(fields) || unit.isLargerThan(Unit.DAY)) {
            throw new RangeErrorException(RELATIVE_TO_REQUIRED);
        }
        final var totalNanos = new BigDecimal(DurationMath.totalNanoseconds(fields));
        final var perUnit = new BigDecimal(DurationMath.nanosPerUnit(unit));
        return new JsNumber(totalNanos.divide(perUnit, new java.math.MathContext(50)).doubleValue());
    }

    public static JsValue toStringMethod(JsTemporalDuration receiver, JsValue optionsArg, InterpreterOps ops) {
        if (isAbsent(optionsArg)) {
            return new JsString(receiver.toString());
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("Temporal.Duration.prototype.toString options must be an object");
        }
        final var digitsValue = ops.getMember(optionsArg, new JsString("fractionalSecondDigits"));
        Integer fractionalSecondDigits = isAbsent(digitsValue) ? null : parseFractionalDigits(digitsValue, ops);
        final var roundingModeValue = ops.getMember(optionsArg, new JsString("roundingMode"));
        final var mode = isAbsent(roundingModeValue)
                ? RoundingMode.TRUNC
                : RoundingMode.parse(JsCoercion.toStr(roundingModeValue, ops));
        final var smallestUnitValue = ops.getMember(optionsArg, new JsString("smallestUnit"));
        var toFormat = receiver.getFields();
        if (!isAbsent(smallestUnitValue)) {
            final var unit = parseFractionalUnit(JsCoercion.toStr(smallestUnitValue, ops));
            toFormat = roundFractionalTail(toFormat, unit, mode, 1);
            fractionalSecondDigits = digitsForUnit(unit);
        } else if (fractionalSecondDigits != null) {
            final var increment = (long) Math.pow(10, 9 - fractionalSecondDigits);
            toFormat = roundFractionalTail(toFormat, Unit.NANOSECOND, mode, increment);
        }
        return new JsString(TemporalFormatter.formatDuration(toFormat, fractionalSecondDigits));
    }

    public static DurationFields roundFractionalTail(DurationFields fields, Unit unit, RoundingMode mode,
            long roundingIncrement) {
        final var tail = new DurationFields(0, 0, 0, fields.days(), fields.hours(), fields.minutes(), fields.seconds(),
                fields.milliseconds(), fields.microseconds(), fields.nanoseconds());
        final var rounded = DurationMath.roundDuration(tail, unit, roundingIncrement, mode, tailLargestUnit(fields));
        final var result = new DurationFields(fields.years(), fields.months(), fields.weeks(), rounded.days(),
                rounded.hours(), rounded.minutes(), rounded.seconds(), rounded.milliseconds(), rounded.microseconds(),
                rounded.nanoseconds());
        DurationMath.validate(result);
        return result;
    }

    public static Unit tailLargestUnit(DurationFields fields) {
        if (fields.days() != 0) {
            return Unit.DAY;
        }
        if (fields.hours() != 0) {
            return Unit.HOUR;
        }
        if (fields.minutes() != 0) {
            return Unit.MINUTE;
        }
        return Unit.SECOND;
    }

    public static Unit tailLargestUnitForAdd(DurationFields fields) {
        if (fields.days() != 0) {
            return Unit.DAY;
        }
        if (fields.hours() != 0) {
            return Unit.HOUR;
        }
        if (fields.minutes() != 0) {
            return Unit.MINUTE;
        }
        if (fields.seconds() != 0) {
            return Unit.SECOND;
        }
        if (fields.milliseconds() != 0) {
            return Unit.MILLISECOND;
        }
        if (fields.microseconds() != 0) {
            return Unit.MICROSECOND;
        }
        return Unit.NANOSECOND;
    }

    public static Unit parseFractionalUnit(String value) {
        final var unit = Unit.parseTemporalUnit(value);
        if (unit != Unit.SECOND && unit != Unit.MILLISECOND && unit != Unit.MICROSECOND && unit != Unit.NANOSECOND) {
            throw new RangeErrorException(
                    "smallestUnit must be one of seconds/milliseconds/microseconds/nanoseconds, got " + value);
        }
        return unit;
    }

    private static int digitsForUnit(Unit unit) {
        return switch (unit) {
            case SECOND -> 0;
            case MILLISECOND -> 3;
            case MICROSECOND -> 6;
            default -> 9;
        };
    }

    public static Integer parseFractionalDigits(JsValue value, InterpreterOps ops) {
        if (!(value instanceof JsNumber number)) {
            final var str = JsCoercion.toStr(value, ops);
            if (!"auto".equals(str)) {
                throw new RangeErrorException(JsCoercion.toStr(value) + " is not a number and converts to the string '"
                        + str + "' which is not valid for fractionalSecondDigits");
            }
            return null;
        }
        final var raw = number.getValue();
        if (!Double.isFinite(raw)) {
            throw new RangeErrorException("fractionalSecondDigits must be 0-9 or \"auto\", got " + raw);
        }
        final var floored = Math.floor(raw);
        if (floored < 0 || floored > 9) {
            throw new RangeErrorException(
                    "fractionalSecondDigits " + raw + " floors to " + (long) floored + " and is out of range");
        }
        return (int) floored;
    }

    public static JsObject singleKeyOptions(String key, JsString value) {
        final var options = new JsObject();
        options.setProto(null);
        options.set(key, value);
        return options;
    }

    private DurationRounding() {
    }
}

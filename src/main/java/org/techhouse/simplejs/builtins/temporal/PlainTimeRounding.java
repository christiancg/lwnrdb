package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.DbTimeBuiltins.member;
import static org.techhouse.simplejs.builtins.TemporalPlainTimeBuiltins.fromNanosOfDay;
import static org.techhouse.simplejs.builtins.TemporalPlainTimeBuiltins.toIntegerWithTruncation;
import static org.techhouse.simplejs.builtins.temporal.PlainTimeDifference.requireTimeUnit;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.nanosPerUnit;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.toNanosOfDay;
import static org.techhouse.simplejs.internal.temporal.TemporalRounding.roundNonNegative;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_DAY;

import java.math.BigInteger;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class PlainTimeRounding {
    public static void validateRoundingIncrement(long increment, Unit unit) {
        final var maximum = switch (unit) {
            case HOUR -> 24;
            case MINUTE, SECOND -> 60;
            case MILLISECOND, MICROSECOND, NANOSECOND -> 1000;
            default -> throw new RangeErrorException("Invalid unit for time rounding: " + unit);
        };
        if (increment < 1 || maximum % increment != 0 || increment == maximum) {
            throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit " + unit.singular());
        }
    }

    public static JsValue optionsObject(JsValue value) {
        if (value instanceof JsString) {
            final var obj = new JsObject();
            obj.set("smallestUnit", value);
            obj.setProto(null);
            return obj;
        }
        return value;
    }

    public static JsValue round(JsTemporalPlainTime receiver, JsValue roundToArg, InterpreterOps ops) {
        if (roundToArg instanceof JsUndefined) {
            throw new TypeErrorException("round() requires an options parameter");
        }
        final var options = optionsObject(roundToArg);
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a string");
        }
        Long incrementRaw = null;
        final var incrementValue = member(options, "roundingIncrement", ops);
        if (!(incrementValue instanceof JsUndefined)) {
            incrementRaw = (long) toIntegerWithTruncation(incrementValue, ops);
        }
        String modeStr = null;
        final var modeValue = member(options, "roundingMode", ops);
        if (!(modeValue instanceof JsUndefined)) {
            modeStr = JsCoercion.toStr(modeValue, ops);
        }
        String smallestUnitStr = null;
        final var smallestUnitValue = member(options, "smallestUnit", ops);
        if (!(smallestUnitValue instanceof JsUndefined)) {
            smallestUnitStr = JsCoercion.toStr(smallestUnitValue, ops);
        }
        if (smallestUnitStr == null) {
            throw new RangeErrorException("smallestUnit is required");
        }
        final var smallestUnit = Unit.parseTemporalUnit(smallestUnitStr);
        requireTimeUnit(smallestUnit);
        final var increment = incrementRaw == null ? 1L : incrementRaw;
        if (incrementRaw != null && (increment < 1 || increment > 1_000_000_000)) {
            throw new RangeErrorException("roundingIncrement out of range: " + increment);
        }
        validateRoundingIncrement(increment, smallestUnit);
        final var mode = modeStr == null ? RoundingMode.HALF_EXPAND : RoundingMode.parse(modeStr);
        return new JsTemporalPlainTime(roundToUnit(receiver.getFields(), smallestUnit, increment, mode));
    }

    public static IsoTimeFields roundToUnit(IsoTimeFields fields, Unit unit, long increment, RoundingMode mode) {
        final var incrementNanos = nanosPerUnit(unit).multiply(BigInteger.valueOf(increment));
        final var rounded = roundNonNegative(toNanosOfDay(fields), incrementNanos, mode).mod(NANOS_PER_DAY);
        return fromNanosOfDay(rounded.longValueExact());
    }

    public static IsoTimeFields roundToFractionalDigits(IsoTimeFields fields, int digits, RoundingMode mode) {
        final var incrementNanos = BigInteger.TEN.pow(9 - digits);
        final var rounded = roundNonNegative(toNanosOfDay(fields), incrementNanos, mode).mod(NANOS_PER_DAY);
        return fromNanosOfDay(rounded.longValueExact());
    }

    private PlainTimeRounding() {
    }
}

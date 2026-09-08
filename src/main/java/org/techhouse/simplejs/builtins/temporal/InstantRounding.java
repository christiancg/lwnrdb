package org.techhouse.simplejs.builtins.temporal;

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
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class InstantRounding {
    public static JsValue roundOptionsObject(JsValue value) {
        if (value instanceof JsString) {
            final var obj = new JsObject();
            obj.setProto(null);
            obj.set("smallestUnit", value);
            return obj;
        }
        return value;
    }

    public static JsValue round(JsTemporalInstant receiver, JsValue optionsArg, InterpreterOps ops) {
        final var options = roundOptionsObject(optionsArg);
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("Temporal.Instant.prototype.round requires an options object");
        }
        final var increment = incrementOption(options, ops);
        final var mode = roundingModeOption(options, RoundingMode.HALF_EXPAND, ops);
        final var smallestUnitValue = ops.getMember(options, new JsString("smallestUnit"));
        if (smallestUnitValue == null || smallestUnitValue instanceof JsUndefined) {
            throw new RangeErrorException("round() requires a smallestUnit option");
        }
        final var smallestUnit = Unit.parseTemporalUnit(JsCoercion.toStr(smallestUnitValue, ops));
        if (smallestUnit.isLargerThan(Unit.HOUR)) {
            throw new RangeErrorException(
                    "Invalid smallestUnit for Temporal.Instant.prototype.round: " + smallestUnit.singular());
        }
        validateRoundIncrementForDay(smallestUnit, increment);
        final var incrementNanos = nanosPerUnit(smallestUnit).multiply(BigInteger.valueOf(increment));
        return JsTemporalInstant
                .fromEpochNanoseconds(roundNanosecondsAsIfPositive(receiver.epochNanoseconds(), incrementNanos, mode));
    }

    public static void validateRoundIncrementForDay(Unit unit, long increment) {
        final var maximum = NANOS_PER_DAY.divide(nanosPerUnit(unit)).longValueExact();
        if (increment < 1 || increment > maximum || maximum % increment != 0) {
            throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit " + unit.singular());
        }
    }

    public static long incrementOption(JsValue options, InterpreterOps ops) {
        if (options == null || options instanceof JsUndefined) {
            return 1;
        }
        final var raw = ops.getMember(options, new JsString("roundingIncrement"));
        if (raw == null || raw instanceof JsUndefined) {
            return 1;
        }
        final var value = JsCoercion.toNumber(raw, ops);
        if (!Double.isFinite(value)) {
            throw new RangeErrorException("roundingIncrement must be a positive integer");
        }
        final var truncated = (long) value;
        if (truncated < 1 || truncated > 1_000_000_000L) {
            throw new RangeErrorException("roundingIncrement must be a positive integer");
        }
        return truncated;
    }

    public static void validateIncrementForUnit(Unit unit, long increment) {
        final var max = maxIncrementFor(unit);
        if (increment < 1 || increment >= max || max % increment != 0) {
            throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit " + unit.singular());
        }
    }

    public static long maxIncrementFor(Unit unit) {
        return switch (unit) {
            case DAY -> 1;
            case HOUR -> 24;
            case MINUTE, SECOND -> 60;
            default -> 1000;
        };
    }

    public static RoundingMode roundingModeOption(JsValue options, RoundingMode fallback, InterpreterOps ops) {
        if (options == null || options instanceof JsUndefined) {
            return fallback;
        }
        final var raw = ops.getMember(options, new JsString("roundingMode"));
        if (raw == null || raw instanceof JsUndefined) {
            return fallback;
        }
        return RoundingMode.parse(JsCoercion.toStr(raw, ops));
    }

    public static Unit unitOption(JsValue options, Unit fallback, InterpreterOps ops) {
        if (options == null || options instanceof JsUndefined) {
            return fallback;
        }
        final var raw = ops.getMember(options, new JsString("smallestUnit"));
        if (raw == null || raw instanceof JsUndefined) {
            return fallback;
        }
        return Unit.parseTemporalUnit(JsCoercion.toStr(raw, ops));
    }

    public static Unit unitOptionOrAuto(JsValue options, InterpreterOps ops) {
        if (options == null || options instanceof JsUndefined) {
            return null;
        }
        final var raw = ops.getMember(options, new JsString("largestUnit"));
        if (raw == null || raw instanceof JsUndefined) {
            return null;
        }
        final var str = JsCoercion.toStr(raw, ops);
        return "auto".equals(str) ? null : Unit.parseTemporalUnit(str);
    }

    private static BigInteger nanosPerUnit(Unit unit) {
        return switch (unit) {
            case DAY -> NANOS_PER_DAY;
            case HOUR -> NANOS_PER_HOUR;
            case MINUTE -> NANOS_PER_MINUTE;
            case SECOND -> NANOS_PER_SECOND;
            case MILLISECOND -> NANOS_PER_MILLI;
            case MICROSECOND -> NANOS_PER_MICRO;
            case NANOSECOND -> BigInteger.ONE;
            default -> throw new RangeErrorException("Unsupported unit for Temporal.Instant: " + unit.singular());
        };
    }

    public static BigInteger roundNanoseconds(BigInteger value, BigInteger increment, RoundingMode mode) {
        final var divRem = value.divideAndRemainder(increment);
        final var quotient = divRem[0];
        final var remainder = divRem[1];
        if (remainder.signum() == 0) {
            return value;
        }
        final var sign = value.signum();
        final var remainderAbs = remainder.abs();
        final var cmp = remainderAbs.shiftLeft(1).compareTo(increment);
        final var roundedQuotient = switch (mode) {
            case TRUNC -> quotient;
            case CEIL -> sign > 0 ? quotient.add(BigInteger.ONE) : quotient;
            case FLOOR -> sign < 0 ? quotient.subtract(BigInteger.ONE) : quotient;
            case EXPAND -> awayFromZero(quotient, sign);
            case HALF_EXPAND -> cmp >= 0 ? awayFromZero(quotient, sign) : quotient;
            case HALF_TRUNC -> cmp > 0 ? awayFromZero(quotient, sign) : quotient;
            case HALF_CEIL -> halfDirectional(quotient, cmp, sign, true);
            case HALF_FLOOR -> halfDirectional(quotient, cmp, sign, false);
            case HALF_EVEN -> halfEven(quotient, cmp, sign);
        };
        return roundedQuotient.multiply(increment);
    }

    public static BigInteger roundNanosecondsAsIfPositive(BigInteger value, BigInteger increment, RoundingMode mode) {
        final var divRem = value.divideAndRemainder(increment);
        var floorQuotient = divRem[0];
        var floorRemainder = divRem[1];
        if (floorRemainder.signum() < 0) {
            floorQuotient = floorQuotient.subtract(BigInteger.ONE);
            floorRemainder = floorRemainder.add(increment);
        }
        if (floorRemainder.signum() == 0) {
            return value;
        }
        final var ceilQuotient = floorQuotient.add(BigInteger.ONE);
        final var cmp = floorRemainder.shiftLeft(1).compareTo(increment);
        final var roundedQuotient = switch (mode) {
            case TRUNC, FLOOR -> floorQuotient;
            case EXPAND, CEIL -> ceilQuotient;
            case HALF_EXPAND, HALF_CEIL -> cmp >= 0 ? ceilQuotient : floorQuotient;
            case HALF_TRUNC, HALF_FLOOR -> cmp > 0 ? ceilQuotient : floorQuotient;
            case HALF_EVEN -> cmp > 0 || (cmp == 0 && floorQuotient.testBit(0)) ? ceilQuotient : floorQuotient;
        };
        return roundedQuotient.multiply(increment);
    }

    public static BigInteger halfDirectional(BigInteger quotient, int cmp, int sign, boolean tieTowardPositive) {
        if (cmp > 0) {
            return awayFromZero(quotient, sign);
        }
        if (cmp == 0) {
            final var tieGoesAway = tieTowardPositive ? sign > 0 : sign < 0;
            return tieGoesAway ? awayFromZero(quotient, sign) : quotient;
        }
        return quotient;
    }

    public static BigInteger halfEven(BigInteger quotient, int cmp, int sign) {
        if (cmp > 0) {
            return awayFromZero(quotient, sign);
        }
        if (cmp == 0) {
            return quotient.testBit(0) ? awayFromZero(quotient, sign) : quotient;
        }
        return quotient;
    }

    public static BigInteger awayFromZero(BigInteger quotient, int sign) {
        return sign >= 0 ? quotient.add(BigInteger.ONE) : quotient.subtract(BigInteger.ONE);
    }

    private InstantRounding() {
    }
}

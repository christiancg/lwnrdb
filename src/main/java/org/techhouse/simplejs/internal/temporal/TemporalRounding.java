package org.techhouse.simplejs.internal.temporal;

import static org.techhouse.simplejs.internal.temporal.TimeUnits.TWO;

import java.math.BigInteger;
import org.techhouse.simplejs.exceptions.RangeErrorException;

public final class TemporalRounding {
    public static int digitsForUnit(Unit unit) {
        return switch (unit) {
            case SECOND -> 0;
            case MILLISECOND -> 3;
            case MICROSECOND -> 6;
            case NANOSECOND -> 9;
            default -> throw new RangeErrorException(
                    "smallestUnit must be minute, second, millisecond, microsecond, or nanosecond");
        };
    }

    public static BigInteger roundNonNegative(BigInteger value, BigInteger increment, RoundingMode mode) {
        final var dm = value.divideAndRemainder(increment);
        final var quotient = dm[0];
        final var remainder = dm[1];
        if (remainder.signum() == 0) {
            return value;
        }
        final var doubled = remainder.multiply(TWO);
        final var roundedQuotient = switch (mode) {
            case TRUNC, FLOOR -> quotient;
            case CEIL, EXPAND -> quotient.add(BigInteger.ONE);
            case HALF_EXPAND, HALF_CEIL -> doubled.compareTo(increment) >= 0 ? quotient.add(BigInteger.ONE) : quotient;
            case HALF_TRUNC, HALF_FLOOR -> doubled.compareTo(increment) > 0 ? quotient.add(BigInteger.ONE) : quotient;
            case HALF_EVEN -> {
                final var cmp = doubled.compareTo(increment);
                if (cmp > 0) {
                    yield quotient.add(BigInteger.ONE);
                }
                yield cmp < 0 ? quotient : (quotient.testBit(0) ? quotient.add(BigInteger.ONE) : quotient);
            }
        };
        return roundedQuotient.multiply(increment);
    }

    public static void requireSecondOrSmallerUnit(Unit unit) {
        if (unit.isLargerThan(Unit.SECOND)) {
            throw new RangeErrorException(
                    "smallestUnit must be minute, second, millisecond, microsecond, or nanosecond");
        }
    }

    private TemporalRounding() {
    }
}

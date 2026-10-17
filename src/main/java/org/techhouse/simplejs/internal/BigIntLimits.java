package org.techhouse.simplejs.internal;

import java.math.BigInteger;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;

public final class BigIntLimits {
    public static final long MAX_BITS = 1L << 24;
    public static final String EXCEEDED = "Maximum BigInt size exceeded";
    public static final int MAX_PARSE_DIGITS = 65_536;

    private static final long FREE_BITS = Long.SIZE;
    private static final int SIGN_AND_ROUNDING_CHARS = 2;
    private static final int DIGITS_PER_GROUP = 3;
    private static final double LOG_TWO = Math.log(2);
    private static final long TO_STRING_WORK_DIVISOR = 1L << 20;

    private BigIntLimits() {
    }

    public static void checkBits(long bits, InterpreterOps ops) {
        if (bits > MAX_BITS || bits < 0) {
            throw new RangeErrorException(EXCEEDED);
        }
        if (bits > FREE_BITS) {
            InterpreterOps.charge(ops, (bits + Byte.SIZE - 1) / Byte.SIZE);
        }
    }

    public static void checkWidening(BigInteger a, BigInteger b, InterpreterOps ops) {
        checkBits(Math.max(a.bitLength(), b.bitLength()) + 1L, ops);
    }

    public static void checkWidening(BigInteger a, InterpreterOps ops) {
        checkBits(a.bitLength() + 1L, ops);
    }

    public static void chargeText(BigInteger value, int radix, InterpreterOps ops) {
        if (value.bitLength() > FREE_BITS) {
            paceConversion(value, ops);
            InterpreterOps.chargeChars(ops, digits(value, radix));
        }
    }

    public static void chargeGroupedText(BigInteger value, InterpreterOps ops) {
        if (value.bitLength() > FREE_BITS) {
            paceConversion(value, ops);
            final var digits = digits(value, 10);
            InterpreterOps.chargeChars(ops, digits + digits / DIGITS_PER_GROUP);
        }
    }

    private static void paceConversion(BigInteger value, InterpreterOps ops) {
        final var bits = (long) value.bitLength();
        InterpreterOps.paceWork(ops, bits * bits / TO_STRING_WORK_DIVISOR);
    }

    private static long digits(BigInteger value, int radix) {
        return (long) Math.ceil(value.bitLength() * LOG_TWO / Math.log(radix)) + SIGN_AND_ROUNDING_CHARS;
    }

    public static BigInteger add(BigInteger a, BigInteger b, InterpreterOps ops) {
        checkBits(Math.max(a.bitLength(), b.bitLength()) + 1L, ops);
        return a.add(b);
    }

    public static BigInteger subtract(BigInteger a, BigInteger b, InterpreterOps ops) {
        checkBits(Math.max(a.bitLength(), b.bitLength()) + 1L, ops);
        return a.subtract(b);
    }

    public static BigInteger multiply(BigInteger a, BigInteger b, InterpreterOps ops) {
        checkBits((long) a.bitLength() + b.bitLength(), ops);
        return a.multiply(b);
    }

    public static BigInteger pow(BigInteger base, BigInteger exponent, InterpreterOps ops) {
        if (exponent.signum() < 0) {
            throw new RangeErrorException("Exponent must be non-negative");
        }
        if (exponent.signum() == 0) {
            return BigInteger.ONE;
        }
        if (base.signum() == 0 || base.equals(BigInteger.ONE)) {
            return base;
        }
        if (base.equals(BigInteger.ONE.negate())) {
            return exponent.testBit(0) ? base : BigInteger.ONE;
        }
        if (exponent.bitLength() >= Integer.SIZE) {
            throw new RangeErrorException(EXCEEDED);
        }
        final var power = exponent.intValue();
        checkBits((long) base.bitLength() * power, ops);
        return base.pow(power);
    }

    public static BigInteger shiftLeft(BigInteger value, BigInteger shift, InterpreterOps ops) {
        if (shift.signum() < 0) {
            return shiftRight(value, shift.negate(), ops);
        }
        if (value.signum() == 0) {
            return value;
        }
        if (shift.bitLength() >= Integer.SIZE) {
            throw new RangeErrorException(EXCEEDED);
        }
        final var bits = shift.intValue();
        checkBits((long) value.bitLength() + bits, ops);
        return value.shiftLeft(bits);
    }

    public static BigInteger shiftRight(BigInteger value, BigInteger shift, InterpreterOps ops) {
        if (shift.signum() < 0) {
            return shiftLeft(value, shift.negate(), ops);
        }
        if (shift.compareTo(BigInteger.valueOf(value.bitLength())) >= 0) {
            return value.signum() < 0 ? BigInteger.ONE.negate() : BigInteger.ZERO;
        }
        return value.shiftRight(shift.intValue());
    }
}

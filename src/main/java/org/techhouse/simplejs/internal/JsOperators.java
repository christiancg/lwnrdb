package org.techhouse.simplejs.internal;

import java.math.BigInteger;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class JsOperators {
    private static final int UNORDERED = -2;

    private JsOperators() {
    }

    public static JsValue binary(String operator, JsValue left, JsValue right) {
        return binary(operator, left, right, null);
    }

    public static JsValue binary(String operator, JsValue left, JsValue right, InterpreterOps ops) {
        return switch (operator) {
            case "+" -> add(left, right, ops);
            case "-", "*", "/", "%", "**" -> arithmetic(operator, left, right, ops);
            case "&", "|", "^", "<<", ">>", ">>>" -> bitwise(operator, left, right, ops);
            case "<", "<=", ">", ">=" -> JsBoolean.of(relational(operator, left, right, ops));
            case "==" -> JsBoolean.of(looseEquals(left, right, ops));
            case "!=" -> JsBoolean.of(!looseEquals(left, right, ops));
            case "===" -> JsBoolean.of(strictEquals(left, right));
            case "!==" -> JsBoolean.of(!strictEquals(left, right));
            default -> throw new TypeErrorException("Unknown binary operator: " + operator);
        };
    }

    public static boolean strictEquals(JsValue left, JsValue right) {
        if (left.getType() != right.getType()) {
            return false;
        }
        return switch (left) {
            case JsNumber n -> n.getValue() == ((JsNumber) right).getValue();
            case JsString s -> s.getValue().equals(((JsString) right).getValue());
            case JsBoolean b -> b.getValue() == ((JsBoolean) right).getValue();
            case JsBigInt b -> b.getValue().equals(((JsBigInt) right).getValue());
            case JsUndefined ignored -> true;
            case JsNull ignored -> true;
            default -> left == right;
        };
    }

    public static boolean looseEquals(JsValue left, JsValue right, InterpreterOps ops) {
        if (left.getType() == right.getType()) {
            return strictEquals(left, right);
        }
        final var leftNullish = left instanceof JsNull || left instanceof JsUndefined;
        final var rightNullish = right instanceof JsNull || right instanceof JsUndefined;
        if (leftNullish || rightNullish) {
            return leftNullish && rightNullish;
        }
        // Two objects of different internal shapes are still both Objects to the spec, so identity
        // decides and neither side is coerced.
        if (JsCoercion.isObject(left) && JsCoercion.isObject(right)) {
            return left == right;
        }
        if (left instanceof JsBoolean) {
            return looseEquals(new JsNumber(JsCoercion.toNumber(left)), right, ops);
        }
        if (right instanceof JsBoolean) {
            return looseEquals(left, new JsNumber(JsCoercion.toNumber(right)), ops);
        }
        if (JsCoercion.isObject(left)) {
            return looseEquals(JsCoercion.toPrimitive(left, "default", ops), right, ops);
        }
        if (JsCoercion.isObject(right)) {
            return looseEquals(left, JsCoercion.toPrimitive(right, "default", ops), ops);
        }
        return looseEqualsPrimitive(left, right);
    }

    private static boolean looseEqualsPrimitive(JsValue left, JsValue right) {
        if (left instanceof JsNumber && right instanceof JsString) {
            return ((JsNumber) left).getValue() == JsCoercion.toNumber(right);
        }
        if (left instanceof JsString && right instanceof JsNumber) {
            return JsCoercion.toNumber(left) == ((JsNumber) right).getValue();
        }
        if (left instanceof JsBigInt && right instanceof JsNumber) {
            return bigEqualsNumber((JsBigInt) left, ((JsNumber) right).getValue());
        }
        if (left instanceof JsNumber && right instanceof JsBigInt) {
            return bigEqualsNumber((JsBigInt) right, ((JsNumber) left).getValue());
        }
        if (left instanceof JsBigInt && right instanceof JsString) {
            return bigEqualsString((JsBigInt) left, ((JsString) right).getValue());
        }
        if (left instanceof JsString && right instanceof JsBigInt) {
            return bigEqualsString((JsBigInt) right, ((JsString) left).getValue());
        }
        return false;
    }

    public static JsValue unary(String operator, JsValue operand) {
        return unary(operator, operand, null);
    }

    public static JsValue unary(String operator, JsValue operand, InterpreterOps ops) {
        return switch (operator) {
            case "!" -> JsBoolean.of(!JsCoercion.toBoolean(operand));
            case "-" -> negate(operand, ops);
            case "+" -> new JsNumber(JsCoercion.toNumber(operand, ops));
            case "~" -> bitwiseNot(operand, ops);
            case "typeof" -> new JsString(JsCoercion.typeOf(operand));
            case "void" -> JsUndefined.getInstance();
            default -> throw new TypeErrorException("Unknown unary operator: " + operator);
        };
    }

    public static JsValue delta(JsValue operand, boolean increment) {
        return delta(operand, increment, null);
    }

    public static JsValue delta(JsValue operand, boolean increment, InterpreterOps ops) {
        final var numeric = JsCoercion.toNumeric(operand, ops);
        if (numeric instanceof JsBigInt b) {
            final var one = BigInteger.ONE;
            return new JsBigInt(increment ? b.getValue().add(one) : b.getValue().subtract(one));
        }
        final var current = ((JsNumber) numeric).getValue();
        return new JsNumber(increment ? current + 1 : current - 1);
    }

    private static JsValue negate(JsValue operand, InterpreterOps ops) {
        final var numeric = JsCoercion.toNumeric(operand, ops);
        if (numeric instanceof JsBigInt b) {
            return new JsBigInt(b.getValue().negate());
        }
        return new JsNumber(-((JsNumber) numeric).getValue());
    }

    private static JsValue bitwiseNot(JsValue operand, InterpreterOps ops) {
        final var numeric = JsCoercion.toNumeric(operand, ops);
        if (numeric instanceof JsBigInt b) {
            return new JsBigInt(b.getValue().not());
        }
        return new JsNumber(~NumberFormatter.toInt32(((JsNumber) numeric).getValue()));
    }

    private static JsValue add(JsValue left, JsValue right, InterpreterOps ops) {
        final var leftPrim = JsCoercion.toPrimitive(left, "default", ops);
        final var rightPrim = JsCoercion.toPrimitive(right, "default", ops);
        if (leftPrim instanceof JsString || rightPrim instanceof JsString) {
            final var leftText = JsCoercion.toStr(leftPrim);
            final var rightText = JsCoercion.toStr(rightPrim);
            // Only the appended delta is charged, not the combined result: `s += "x"` in a loop would
            // otherwise cost quadratically and reject ordinary string building, while `s = s + s` -
            // the case tick() cannot see, since it doubles in one instruction - has a delta equal to
            // the whole accumulated string and is still bounded.
            InterpreterOps.chargeChars(ops, rightText.length());
            return new JsString(leftText + rightText);
        }
        if (leftPrim instanceof JsBigInt && rightPrim instanceof JsBigInt) {
            return new JsBigInt(((JsBigInt) leftPrim).getValue().add(((JsBigInt) rightPrim).getValue()));
        }
        requireNoMixedBigInt(leftPrim, rightPrim);
        return new JsNumber(JsCoercion.toNumber(leftPrim) + JsCoercion.toNumber(rightPrim));
    }

    private static JsValue arithmetic(String operator, JsValue left, JsValue right, InterpreterOps ops) {
        final var lnum = JsCoercion.toNumeric(left, ops);
        final var rnum = JsCoercion.toNumeric(right, ops);
        if (lnum instanceof JsBigInt x && rnum instanceof JsBigInt y) {
            return bigIntArithmetic(operator, x.getValue(), y.getValue());
        }
        requireNoMixedBigInt(lnum, rnum);
        assert lnum instanceof JsNumber;
        final var a = ((JsNumber) lnum).getValue();
        final var b = ((JsNumber) rnum).getValue();
        return new JsNumber(switch (operator) {
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> a / b;
            case "%" -> a % b;
            case "**" -> Math.pow(a, b);
            default -> throw new TypeErrorException("Unknown arithmetic operator: " + operator);
        });
    }

    private static JsValue bigIntArithmetic(String operator, BigInteger a, BigInteger b) {
        return new JsBigInt(switch (operator) {
            case "-" -> a.subtract(b);
            case "*" -> a.multiply(b);
            case "/" -> requireNonZero(b) ? a.divide(b) : a;
            case "%" -> requireNonZero(b) ? a.remainder(b) : a;
            case "**" -> bigIntPow(a, b);
            default -> throw new TypeErrorException("Unknown arithmetic operator: " + operator);
        });
    }

    private static boolean requireNonZero(BigInteger b) {
        if (b.signum() == 0) {
            throw new RangeErrorException("Division by zero");
        }
        return true;
    }

    private static BigInteger bigIntPow(BigInteger a, BigInteger b) {
        if (b.signum() < 0) {
            throw new RangeErrorException("Exponent must be non-negative");
        }
        return a.pow(b.intValueExact());
    }

    private static JsValue bitwise(String operator, JsValue left, JsValue right, InterpreterOps ops) {
        final var lnum = JsCoercion.toNumeric(left, ops);
        final var rnum = JsCoercion.toNumeric(right, ops);
        if (lnum instanceof JsBigInt x && rnum instanceof JsBigInt y) {
            return bigIntBitwise(operator, x.getValue(), y.getValue());
        }
        requireNoMixedBigInt(lnum, rnum);
        if (">>>".equals(operator)) {
            final var result = toUint32(lnum) >>> (toUint32(rnum) & 0x1f);
            return new JsNumber(result);
        }
        final var a = toInt32(lnum);
        final var b = toInt32(rnum);
        return new JsNumber(switch (operator) {
            case "&" -> a & b;
            case "|" -> a | b;
            case "^" -> a ^ b;
            case "<<" -> a << (b & 0x1f);
            case ">>" -> a >> (b & 0x1f);
            default -> throw new TypeErrorException("Unknown bitwise operator: " + operator);
        });
    }

    private static JsValue bigIntBitwise(String operator, BigInteger a, BigInteger b) {
        return new JsBigInt(switch (operator) {
            case "&" -> a.and(b);
            case "|" -> a.or(b);
            case "^" -> a.xor(b);
            case "<<" -> a.shiftLeft(b.intValueExact());
            case ">>" -> a.shiftRight(b.intValueExact());
            default -> throw new TypeErrorException("BigInts have no unsigned right shift");
        });
    }

    private static boolean relational(String operator, JsValue left, JsValue right, InterpreterOps ops) {
        final var leftPrim = JsCoercion.toPrimitive(left, "number", ops);
        final var rightPrim = JsCoercion.toPrimitive(right, "number", ops);
        final var sign = compare(leftPrim, rightPrim);
        if (sign == UNORDERED) {
            return false;
        }
        return switch (operator) {
            case "<" -> sign < 0;
            case "<=" -> sign <= 0;
            case ">" -> sign > 0;
            case ">=" -> sign >= 0;
            default -> throw new TypeErrorException("Unknown relational operator: " + operator);
        };
    }

    // IsLessThan: a BigInt against a String is decided by StringToBigInt, never by a numeric
    // round-trip, so an unparseable string leaves the pair unordered instead of comparing as 0.
    private static int compare(JsValue left, JsValue right) {
        if (left instanceof JsString a && right instanceof JsString b) {
            return Integer.signum(a.getValue().compareTo(b.getValue()));
        }
        if (left instanceof JsBigInt a && right instanceof JsString b) {
            final var parsed = JsCoercion.stringToBigInt(b.getValue());
            return parsed == null ? UNORDERED : Integer.signum(a.getValue().compareTo(parsed));
        }
        if (left instanceof JsString a && right instanceof JsBigInt b) {
            final var parsed = JsCoercion.stringToBigInt(a.getValue());
            return parsed == null ? UNORDERED : Integer.signum(parsed.compareTo(b.getValue()));
        }
        return compareNumeric(left, right);
    }

    private static int compareNumeric(JsValue left, JsValue right) {
        final var a = JsCoercion.toNumeric(left, null);
        final var b = JsCoercion.toNumeric(right, null);
        if (a instanceof JsBigInt x && b instanceof JsBigInt y) {
            return Integer.signum(x.getValue().compareTo(y.getValue()));
        }
        if (a instanceof JsBigInt x) {
            return bigCompareNumber(x.getValue(), ((JsNumber) b).getValue());
        }
        if (b instanceof JsBigInt y) {
            final var flipped = bigCompareNumber(y.getValue(), ((JsNumber) a).getValue());
            return flipped == UNORDERED ? UNORDERED : -flipped;
        }
        return compareDoubles(((JsNumber) a).getValue(), ((JsNumber) b).getValue());
    }

    private static int compareDoubles(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return UNORDERED;
        }
        if (a < b) {
            return -1;
        }
        return a > b ? 1 : 0;
    }

    // A finite double is exactly mantissa x 2^exponent, so scaling whichever side has the smaller
    // exponent compares the two mathematical values without ever rounding through a decimal form -
    // which is what makes 2^53+1 and Number.MAX_VALUE compare against a BigInt correctly.
    private static int bigCompareNumber(BigInteger big, double number) {
        if (Double.isNaN(number)) {
            return UNORDERED;
        }
        if (number == Double.POSITIVE_INFINITY) {
            return -1;
        }
        if (number == Double.NEGATIVE_INFINITY) {
            return 1;
        }
        final var bits = Double.doubleToLongBits(number);
        final var rawExponent = (int) ((bits >> 52) & 0x7ff);
        var mantissa = BigInteger.valueOf(bits & 0x000fffffffffffffL);
        if (rawExponent != 0) {
            mantissa = mantissa.add(BigInteger.ONE.shiftLeft(52));
        }
        if (bits < 0) {
            mantissa = mantissa.negate();
        }
        final var exponent = (rawExponent == 0 ? 1 : rawExponent) - 1075;
        final var scaledNumber = exponent >= 0 ? mantissa.shiftLeft(exponent) : mantissa;
        final var scaledBig = exponent >= 0 ? big : big.shiftLeft(-exponent);
        return Integer.signum(scaledBig.compareTo(scaledNumber));
    }

    private static boolean bigEqualsNumber(JsBigInt big, double number) {
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.floor(number)) {
            return false;
        }
        return bigCompareNumber(big.getValue(), number) == 0;
    }

    private static boolean bigEqualsString(JsBigInt big, String raw) {
        final var parsed = JsCoercion.stringToBigInt(raw);
        return parsed != null && parsed.equals(big.getValue());
    }

    private static void requireNoMixedBigInt(JsValue left, JsValue right) {
        if (left instanceof JsBigInt || right instanceof JsBigInt) {
            throw new TypeErrorException("Cannot mix BigInt and other types, use explicit conversions");
        }
    }

    private static int toInt32(JsValue numeric) {
        return NumberFormatter.toInt32(((JsNumber) numeric).getValue());
    }

    private static long toUint32(JsValue numeric) {
        return toInt32(numeric) & 0xffffffffL;
    }
}

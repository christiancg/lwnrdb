package org.techhouse.simplejs.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
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
        if (left instanceof JsBoolean) {
            return looseEquals(new JsNumber(JsCoercion.toNumber(left)), right, ops);
        }
        if (right instanceof JsBoolean) {
            return looseEquals(left, new JsNumber(JsCoercion.toNumber(right)), ops);
        }
        if (left instanceof JsObject || left instanceof JsArray) {
            return looseEquals(JsCoercion.toPrimitive(left, "default", ops), right, ops);
        }
        if (right instanceof JsObject || right instanceof JsArray) {
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
        if (operand instanceof JsBigInt b) {
            final var one = BigInteger.ONE;
            return new JsBigInt(increment ? b.getValue().add(one) : b.getValue().subtract(one));
        }
        final var current = JsCoercion.toNumber(operand, ops);
        return new JsNumber(increment ? current + 1 : current - 1);
    }

    private static JsValue negate(JsValue operand, InterpreterOps ops) {
        if (operand instanceof JsBigInt b) {
            return new JsBigInt(b.getValue().negate());
        }
        return new JsNumber(-JsCoercion.toNumber(operand, ops));
    }

    private static JsValue bitwiseNot(JsValue operand, InterpreterOps ops) {
        if (operand instanceof JsBigInt b) {
            return new JsBigInt(b.getValue().not());
        }
        return new JsNumber(~toInt32(operand, ops));
    }

    private static JsValue add(JsValue left, JsValue right, InterpreterOps ops) {
        final var leftPrim = JsCoercion.toPrimitive(left, "default", ops);
        final var rightPrim = JsCoercion.toPrimitive(right, "default", ops);
        if (leftPrim instanceof JsString || rightPrim instanceof JsString) {
            return new JsString(JsCoercion.toStr(leftPrim) + JsCoercion.toStr(rightPrim));
        }
        if (leftPrim instanceof JsBigInt && rightPrim instanceof JsBigInt) {
            return new JsBigInt(((JsBigInt) leftPrim).getValue().add(((JsBigInt) rightPrim).getValue()));
        }
        requireNoMixedBigInt(leftPrim, rightPrim);
        return new JsNumber(JsCoercion.toNumber(leftPrim) + JsCoercion.toNumber(rightPrim));
    }

    private static JsValue arithmetic(String operator, JsValue left, JsValue right, InterpreterOps ops) {
        if (left instanceof JsBigInt && right instanceof JsBigInt) {
            return bigIntArithmetic(operator, ((JsBigInt) left).getValue(), ((JsBigInt) right).getValue());
        }
        requireNoMixedBigInt(left, right);
        final var a = JsCoercion.toNumber(left, ops);
        final var b = JsCoercion.toNumber(right, ops);
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
        if (left instanceof JsBigInt && right instanceof JsBigInt) {
            return bigIntBitwise(operator, ((JsBigInt) left).getValue(), ((JsBigInt) right).getValue());
        }
        requireNoMixedBigInt(left, right);
        if (">>>".equals(operator)) {
            final var result = toUint32(left, ops) >>> (toUint32(right, ops) & 0x1f);
            return new JsNumber(result);
        }
        final var a = toInt32(left, ops);
        final var b = toInt32(right, ops);
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
        final int sign;
        if (leftPrim instanceof JsString && rightPrim instanceof JsString) {
            sign = Integer.signum(((JsString) leftPrim).getValue().compareTo(((JsString) rightPrim).getValue()));
        } else {
            sign = compareNumeric(leftPrim, rightPrim);
        }
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

    private static int compareNumeric(JsValue left, JsValue right) {
        if (left instanceof JsBigInt && right instanceof JsBigInt) {
            return Integer.signum(((JsBigInt) left).getValue().compareTo(((JsBigInt) right).getValue()));
        }
        if (left instanceof JsBigInt || right instanceof JsBigInt) {
            final var a = toDecimal(left);
            final var b = toDecimal(right);
            if (a == null || b == null) {
                return UNORDERED;
            }
            return Integer.signum(a.compareTo(b));
        }
        final var a = JsCoercion.toNumber(left);
        final var b = JsCoercion.toNumber(right);
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return UNORDERED;
        }
        return Integer.signum(Double.compare(a, b));
    }

    private static BigDecimal toDecimal(JsValue value) {
        if (value instanceof JsBigInt b) {
            return new BigDecimal(b.getValue());
        }
        final var d = JsCoercion.toNumber(value);
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return null;
        }
        return BigDecimal.valueOf(d);
    }

    private static boolean bigEqualsNumber(JsBigInt big, double number) {
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.floor(number)) {
            return false;
        }
        return new BigDecimal(big.getValue()).compareTo(BigDecimal.valueOf(number)) == 0;
    }

    private static boolean bigEqualsString(JsBigInt big, String raw) {
        try {
            return big.getValue().equals(new BigInteger(raw.strip()));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void requireNoMixedBigInt(JsValue left, JsValue right) {
        if (left instanceof JsBigInt || right instanceof JsBigInt) {
            throw new TypeErrorException("Cannot mix BigInt and other types, use explicit conversions");
        }
    }

    private static int toInt32(JsValue value, InterpreterOps ops) {
        return NumberFormatter.toInt32(JsCoercion.toNumber(value, ops));
    }

    private static long toUint32(JsValue value, InterpreterOps ops) {
        return toInt32(value, ops) & 0xffffffffL;
    }
}

package org.techhouse.simplejs.nodes;

import org.techhouse.simplejs.exceptions.TypeErrorException;

public enum BinaryOperator {
    ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/"), REMAINDER("%"), EXPONENT("**"), BIT_AND("&"), BIT_OR(
            "|"), BIT_XOR("^"), SHIFT_LEFT("<<"), SHIFT_RIGHT(">>"), UNSIGNED_SHIFT_RIGHT(">>>"), LESS("<"), LESS_EQUAL(
                    "<="), GREATER(">"), GREATER_EQUAL(">="), LOOSE_EQUAL("=="), LOOSE_NOT_EQUAL(
                            "!="), STRICT_EQUAL("==="), STRICT_NOT_EQUAL("!=="), INSTANCEOF("instanceof"), IN("in");

    private final String token;

    BinaryOperator(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static BinaryOperator of(String token) {
        return switch (token) {
            case "+" -> ADD;
            case "-" -> SUBTRACT;
            case "*" -> MULTIPLY;
            case "/" -> DIVIDE;
            case "%" -> REMAINDER;
            case "**" -> EXPONENT;
            case "&" -> BIT_AND;
            case "|" -> BIT_OR;
            case "^" -> BIT_XOR;
            case "<<" -> SHIFT_LEFT;
            case ">>" -> SHIFT_RIGHT;
            case ">>>" -> UNSIGNED_SHIFT_RIGHT;
            case "<" -> LESS;
            case "<=" -> LESS_EQUAL;
            case ">" -> GREATER;
            case ">=" -> GREATER_EQUAL;
            case "==" -> LOOSE_EQUAL;
            case "!=" -> LOOSE_NOT_EQUAL;
            case "===" -> STRICT_EQUAL;
            case "!==" -> STRICT_NOT_EQUAL;
            case "instanceof" -> INSTANCEOF;
            case "in" -> IN;
            default -> throw new TypeErrorException("Unknown binary operator: " + token);
        };
    }

    public static BinaryOperator compoundOf(String assignmentOperator) {
        return switch (assignmentOperator) {
            case "=", "&&=", "||=", "??=" -> null;
            default -> of(assignmentOperator.substring(0, assignmentOperator.length() - 1));
        };
    }
}

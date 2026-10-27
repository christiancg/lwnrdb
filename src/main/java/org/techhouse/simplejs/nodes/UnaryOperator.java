package org.techhouse.simplejs.nodes;

import org.techhouse.simplejs.exceptions.TypeErrorException;

public enum UnaryOperator {
    NOT("!"), NEGATE("-"), PLUS("+"), BIT_NOT("~"), TYPEOF("typeof"), VOID("void"), DELETE("delete");

    private final String token;

    UnaryOperator(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static UnaryOperator of(String token) {
        return switch (token) {
            case "!" -> NOT;
            case "-" -> NEGATE;
            case "+" -> PLUS;
            case "~" -> BIT_NOT;
            case "typeof" -> TYPEOF;
            case "void" -> VOID;
            case "delete" -> DELETE;
            default -> throw new TypeErrorException("Unknown unary operator: " + token);
        };
    }
}

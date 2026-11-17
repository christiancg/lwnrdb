package io.github.christiancg.lwnrdb.client.query;

import io.github.christiancg.lwnrdb.client.values.CustomType;
import java.util.Arrays;

public final class MidOps {
    private MidOps() {
    }

    public static MidOperation avg(Object... operands) {
        return array(ArrayOperator.AVG, operands);
    }

    public static MidOperation sum(Object... operands) {
        return array(ArrayOperator.SUM, operands);
    }

    public static MidOperation subs(Object... operands) {
        return array(ArrayOperator.SUBS, operands);
    }

    public static MidOperation max(Object... operands) {
        return array(ArrayOperator.MAX, operands);
    }

    public static MidOperation min(Object... operands) {
        return array(ArrayOperator.MIN, operands);
    }

    public static MidOperation multiply(Object... operands) {
        return array(ArrayOperator.MULTIPLY, operands);
    }

    public static MidOperation divide(Object... operands) {
        return array(ArrayOperator.DIVIDE, operands);
    }

    public static MidOperation pow(Object... operands) {
        return array(ArrayOperator.POW, operands);
    }

    public static MidOperation root(Object... operands) {
        return array(ArrayOperator.ROOT, operands);
    }

    public static MidOperation concat(Object... operands) {
        return array(ArrayOperator.CONCAT, operands);
    }

    public static Literal literal(String text) {
        return new Literal(text);
    }

    public static MidOperation abs(String field) {
        return new FieldFunction(FieldFunction.Function.ABS, field);
    }

    public static MidOperation size(String field) {
        return new FieldFunction(FieldFunction.Function.SIZE, field);
    }

    public static MidOperation cast(String field, CastType type) {
        return new CastOperation(field, type, null);
    }

    public static MidOperation cast(String field, CustomType customType) {
        return new CastOperation(field, CastType.JSON_CUSTOM, customType);
    }

    public static MidOperation script(String source) {
        return new ScriptOperation(source);
    }

    private static MidOperation array(ArrayOperator operator, Object... operands) {
        return new ArrayOperation(operator, Arrays.asList(operands));
    }
}

package org.techhouse.ejson.elements;

import java.util.Map;
import java.util.Set;
import org.techhouse.config.Globals;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;

public abstract class JsonCustom<T> extends JsonString {
    protected T customValue;

    public JsonCustom(T customValue) {
        this.customValue = customValue;
        this.value = "#" + getCustomTypeName() + "(" + customValue + ")";
    }

    public JsonCustom(String strValue) {
        if (strValue == null || strValue.isEmpty() || !strValue.matches(Globals.CUSTOM_JSON_REGEX)) {
            throw new WrongFormatCustomTypeException(getClass().getName());
        }
        this.value = strValue;
        this.customValue = parse();
    }

    public JsonCustom() {
    }

    public T getCustomValue() {
        return customValue;
    }

    public String stringDataValue() {
        return value.substring(value.indexOf('(') + 1, value.length() - 1);
    }

    public abstract String getCustomTypeName();
    protected abstract T parse() throws WrongFormatCustomTypeException;
    public abstract Integer compare(T another);

    // The names of the custom (type-specific) filter operators this type supports, e.g. a geo type
    // supports "distance" and "within". A type that declares none inherits the empty set here, and
    // the matching apply method then throws.
    public Set<String> customOperatorNames() {
        return Set.of();
    }

    // Evaluates a custom operator against this value (the stored document value). args carries the
    // operator's parameters as raw JSON elements (interpreted by the concrete type). Returns whether
    // this value satisfies the operator.
    public boolean applyCustomOperator(String operatorName, Map<String, JsonBaseElement> args) {
        throw new UnsupportedOperationException(getCustomTypeName() + " has no custom operators");
    }

    // The ranking (top-K) operators this type supports (e.g. the vector type's "nearest"). Unlike a
    // predicate operator, a ranking operator scores each value and the FILTER step keeps the highest K.
    public Set<String> customRankingOperatorNames() {
        return Set.of();
    }

    // Scores this value for a ranking operator (higher is better, e.g. cosine similarity).
    public double applyCustomRankingOperator(String operatorName, Map<String, JsonBaseElement> args) {
        throw new UnsupportedOperationException(getCustomTypeName() + " has no ranking operators");
    }

    public static Boolean isJsonCustom(JsonString str) {
        final var value = str.get();
        return value.matches(Globals.CUSTOM_JSON_REGEX);
    }
}

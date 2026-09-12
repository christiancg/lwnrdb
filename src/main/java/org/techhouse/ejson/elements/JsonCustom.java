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

    public Set<String> customOperatorNames() {
        return Set.of();
    }

    public boolean applyCustomOperator(String operatorName, Map<String, JsonBaseElement> args) {
        throw new UnsupportedOperationException(getCustomTypeName() + " has no custom operators");
    }

    public Set<String> customRankingOperatorNames() {
        return Set.of();
    }

    public double applyCustomRankingOperator(String operatorName, Map<String, JsonBaseElement> args) {
        throw new UnsupportedOperationException(getCustomTypeName() + " has no ranking operators");
    }

    public static Boolean isJsonCustom(JsonString str) {
        final var value = str.get();
        return value.matches(Globals.CUSTOM_JSON_REGEX);
    }
}

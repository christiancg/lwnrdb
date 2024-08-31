package org.techhouse.ejson.elements;

import lombok.Getter;
import org.techhouse.config.Globals;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;

@Getter
public abstract class JsonCustom<T> extends JsonString {
    protected T customValue;

    public JsonCustom(T customValue) {
        this.customValue = customValue;
        this.value = getValue();
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

    public String stringDataValue() {
        return value.substring(value.indexOf('(') + 1, value.length() - 1);
    }

    public abstract String getCustomTypeName();
    protected abstract T parse() throws WrongFormatCustomTypeException;
    public abstract Integer compare(T another);

    public static Boolean isJsonCustom(JsonString str) {
        final var value = str.get();
        return value.matches(Globals.CUSTOM_JSON_REGEX);
    }
}

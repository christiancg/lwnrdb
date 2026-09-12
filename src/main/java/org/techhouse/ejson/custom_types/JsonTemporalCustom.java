package org.techhouse.ejson.custom_types;

import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;

public abstract class JsonTemporalCustom<T extends Comparable<? super T>> extends JsonCustom<T> {
    public JsonTemporalCustom(T customValue) {
        super(customValue);
    }

    public JsonTemporalCustom(String strValue) {
        super(strValue);
    }

    public JsonTemporalCustom() {
        super();
    }

    protected abstract T parseValue(String value);

    @Override
    protected T parse() {
        try {
            return parseValue(stringDataValue());
        } catch (Exception e) {
            throw new WrongFormatCustomTypeException(getClass().getName(), e);
        }
    }

    @Override
    public Integer compare(T another) {
        return customValue.compareTo(another);
    }
}

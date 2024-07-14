package org.techhouse.ejson.custom_types;

import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;

import java.time.LocalDateTime;

public class JsonDateTime extends JsonCustom<LocalDateTime> {
    public JsonDateTime(LocalDateTime customValue) {
        super(customValue);
    }

    public JsonDateTime(String strValue) {
        super(strValue);
    }

    public JsonDateTime() {
        super();
    }

    @Override
    public String getCustomTypeName() {
        return "datetime";
    }

    @Override
    protected LocalDateTime parse() {
        try {
            return LocalDateTime.parse(stringDataValue());
        } catch (Exception e) {
            throw new WrongFormatCustomTypeException(getClass().getName(), e);
        }
    }

    @Override
    public Integer compare(LocalDateTime another) {
        return customValue.compareTo(another);
    }
}

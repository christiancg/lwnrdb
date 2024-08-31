package org.techhouse.ejson.custom_types;

import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;

import java.time.LocalTime;

public class JsonTime extends JsonCustom<LocalTime> {
    public JsonTime(LocalTime customValue) {
        super(customValue);
    }

    public JsonTime(String strValue) {
        super(strValue);
    }

    public JsonTime() {
        super();
    }

    @Override
    public String getCustomTypeName() {
        return "time";
    }

    @Override
    protected LocalTime parse() {
        try {
            return LocalTime.parse(stringDataValue());
        } catch (Exception e) {
            throw new WrongFormatCustomTypeException(getClass().getName(), e);
        }
    }

    @Override
    public Integer compare(LocalTime another) {
        return customValue.compareTo(another);
    }
}

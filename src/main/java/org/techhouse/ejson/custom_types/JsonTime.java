package org.techhouse.ejson.custom_types;

import org.techhouse.ejson.elements.JsonCustom;

import java.time.LocalTime;

public class JsonTime extends JsonCustom<LocalTime> {
    @Override
    public String getCustomTypeName() {
        return "time";
    }

    @Override
    protected LocalTime parse() {
        return LocalTime.parse(value);
    }

    @Override
    public Integer compare(LocalTime another) {
        return customValue.compareTo(another);
    }
}

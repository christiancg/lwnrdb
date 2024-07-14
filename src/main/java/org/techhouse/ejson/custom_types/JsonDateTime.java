package org.techhouse.ejson.custom_types;

import org.techhouse.ejson.elements.JsonCustom;

import java.time.LocalDateTime;

public class JsonDateTime extends JsonCustom<LocalDateTime> {
    @Override
    public String getCustomTypeName() {
        return "datetime";
    }

    @Override
    protected LocalDateTime parse() {
        return LocalDateTime.parse(value);
    }

    @Override
    public Integer compare(LocalDateTime another) {
        return customValue.compareTo(another);
    }
}

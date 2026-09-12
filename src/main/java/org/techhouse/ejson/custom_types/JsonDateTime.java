package org.techhouse.ejson.custom_types;

import java.time.LocalDateTime;

public class JsonDateTime extends JsonTemporalCustom<LocalDateTime> {
    public static final String CUSTOM_TYPE_NAME = "datetime";

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
        return CUSTOM_TYPE_NAME;
    }

    @Override
    protected LocalDateTime parseValue(String value) {
        return LocalDateTime.parse(value);
    }
}

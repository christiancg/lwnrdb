package org.techhouse.ejson.custom_types;

import java.time.LocalTime;

public class JsonTime extends JsonTemporalCustom<LocalTime> {
    public static final String CUSTOM_TYPE_NAME = "time";

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
        return CUSTOM_TYPE_NAME;
    }

    @Override
    protected LocalTime parseValue(String value) {
        return LocalTime.parse(value);
    }
}

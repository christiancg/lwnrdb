package org.techhouse.simplejs.values;

import java.time.LocalTime;
import org.techhouse.ejson.custom_types.JsonTime;

public final class JsDbTime extends JsValue {
    private PropertyTable table;

    private final LocalTime value;

    private final JsonTime source;

    public JsDbTime(LocalTime value) {
        this(value, null);
    }

    public JsDbTime(JsonTime source) {
        this(source.getCustomValue(), source);
    }

    private JsDbTime(LocalTime value, JsonTime source) {
        this.value = value;
        this.source = source;
    }

    public LocalTime getValue() {
        return value;
    }

    public JsDbTime copy() {
        return new JsDbTime(value, source);
    }

    public JsonTime toJsonTime() {
        return source != null ? source : new JsonTime(value);
    }

    @Override
    public String toString() {
        return new JsonTime(value).getValue();
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }

    @Override
    public JsValueType getType() {
        return JsValueType.DB_TIME;
    }
}

package org.techhouse.simplejs.values;

import java.time.LocalTime;
import org.techhouse.ejson.custom_types.JsonTime;

public final class JsDbTime extends JsValue {
    private PropertyTable table;

    private final LocalTime value;

    public JsDbTime(LocalTime value) {
        this.value = value;
    }

    public LocalTime getValue() {
        return value;
    }

    public JsonTime toJsonTime() {
        return new JsonTime(value);
    }

    @Override
    public String toString() {
        return toJsonTime().getValue();
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

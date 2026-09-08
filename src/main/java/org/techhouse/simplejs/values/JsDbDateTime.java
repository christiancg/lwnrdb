package org.techhouse.simplejs.values;

import java.time.LocalDateTime;
import org.techhouse.ejson.custom_types.JsonDateTime;

public final class JsDbDateTime extends JsValue {
    private PropertyTable table;

    private final LocalDateTime value;

    public JsDbDateTime(LocalDateTime value) {
        this.value = value;
    }

    public LocalDateTime getValue() {
        return value;
    }

    public JsonDateTime toJsonDateTime() {
        return new JsonDateTime(value);
    }

    @Override
    public String toString() {
        return toJsonDateTime().getValue();
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

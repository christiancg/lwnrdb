package org.techhouse.simplejs.values;

import java.time.LocalDateTime;
import org.techhouse.ejson.custom_types.JsonDateTime;

public final class JsDbDateTime extends JsValue {
    private PropertyTable table;

    private final LocalDateTime value;

    private final JsonDateTime source;

    public JsDbDateTime(LocalDateTime value) {
        this(value, null);
    }

    public JsDbDateTime(JsonDateTime source) {
        this(source.getCustomValue(), source);
    }

    private JsDbDateTime(LocalDateTime value, JsonDateTime source) {
        this.value = value;
        this.source = source;
    }

    public LocalDateTime getValue() {
        return value;
    }

    public JsDbDateTime copy() {
        return new JsDbDateTime(value, source);
    }

    public JsonDateTime toJsonDateTime() {
        return source != null ? source : new JsonDateTime(value);
    }

    @Override
    public String toString() {
        return new JsonDateTime(value).getValue();
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
        return JsValueType.DB_DATE_TIME;
    }
}

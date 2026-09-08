package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;

public final class JsTemporalDuration extends JsValue {
    private PropertyTable table;

    private final DurationFields fields;

    public JsTemporalDuration(DurationFields fields) {
        DurationMath.validate(fields);
        this.fields = fields;
    }

    public DurationFields getFields() {
        return fields;
    }

    public int sign() {
        return DurationMath.sign(fields);
    }

    public boolean blank() {
        return sign() == 0;
    }

    @Override
    public String toString() {
        return TemporalFormatter.formatDuration(fields);
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

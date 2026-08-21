package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;

/**
 * A JavaScript {@code Temporal.Duration} value: ten signed fields (years..nanoseconds), all of the
 * same sign or zero, each within IsValidDuration's range (enforced by {@link DurationMath#validate}
 * in this class's own constructor - the single chokepoint every Duration passes through). Carries no
 * calendar dependency, unlike every other Temporal type.
 */
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

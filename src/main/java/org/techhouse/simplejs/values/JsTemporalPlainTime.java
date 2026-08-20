package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;

/**
 * A JavaScript {@code Temporal.PlainTime} value: a wall-clock time with no date or time zone
 * component. Backed by T0's {@link IsoTimeFields} record, following the same shape as
 * {@link JsDate} (a bare data carrier; all arithmetic/formatting logic lives in
 * {@code TemporalPlainTimeBuiltins}).
 */
public final class JsTemporalPlainTime extends JsValue {
    private PropertyTable table;

    private final IsoTimeFields fields;

    public JsTemporalPlainTime(IsoTimeFields fields) {
        this.fields = fields;
    }

    public IsoTimeFields getFields() {
        return fields;
    }

    public static int compare(JsTemporalPlainTime a, JsTemporalPlainTime b) {
        return compareFields(a.fields, b.fields);
    }

    public static int compareFields(IsoTimeFields a, IsoTimeFields b) {
        if (a.hour() != b.hour()) {
            return Integer.compare(a.hour(), b.hour());
        }
        if (a.minute() != b.minute()) {
            return Integer.compare(a.minute(), b.minute());
        }
        if (a.second() != b.second()) {
            return Integer.compare(a.second(), b.second());
        }
        if (a.millisecond() != b.millisecond()) {
            return Integer.compare(a.millisecond(), b.millisecond());
        }
        if (a.microsecond() != b.microsecond()) {
            return Integer.compare(a.microsecond(), b.microsecond());
        }
        return Integer.compare(a.nanosecond(), b.nanosecond());
    }

    @Override
    public String toString() {
        return TemporalFormatter.formatTime(fields, null);
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

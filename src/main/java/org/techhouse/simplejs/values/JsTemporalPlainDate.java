package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;

/**
 * A JavaScript {@code Temporal.PlainDate} value: a calendar date with no time-of-day or time zone,
 * "iso8601" calendar only (see the feature plan's scope-defining finding — every non-ISO-calendar
 * test262 case lives under the already-excluded {@code intl402/} tree).
 */
public final class JsTemporalPlainDate extends JsValue {
    private PropertyTable table;
    private final Iso8601Fields fields;

    public JsTemporalPlainDate(Iso8601Fields fields) {
        this.fields = fields;
    }

    public Iso8601Fields fields() {
        return fields;
    }

    public int year() {
        return fields.year();
    }

    public int month() {
        return fields.month();
    }

    public int day() {
        return fields.day();
    }

    public boolean sameDate(JsTemporalPlainDate other) {
        return IsoCalendar.compareIsoDate(fields, other.fields) == 0;
    }

    @Override
    public String toString() {
        return TemporalFormatter.formatDate(fields);
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

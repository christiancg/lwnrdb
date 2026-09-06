package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;

/**
 * A JavaScript {@code Temporal.PlainMonthDay} value: a calendar month and day with no year meaning
 * of its own, "iso8601" calendar only (see the feature plan's scope-defining finding). The wrapped
 * {@link Iso8601Fields#year()} is the spec-mandated "reference ISO year" (defaults to 1972, a leap
 * year, so "02-29" round-trips) - it is never exposed as an accessor, unlike {@code PlainYearMonth}
 * which does expose its own analogous {@code year} field.
 */
public final class JsTemporalPlainMonthDay extends JsValue {
    public static final int DEFAULT_REFERENCE_ISO_YEAR = 1972;

    private PropertyTable table;
    private final Iso8601Fields fields;

    public JsTemporalPlainMonthDay(Iso8601Fields fields) {
        this.fields = fields;
    }

    public Iso8601Fields fields() {
        return fields;
    }

    public int month() {
        return fields.month();
    }

    public int day() {
        return fields.day();
    }

    public int referenceISOYear() {
        return fields.year();
    }

    @Override
    public String toString() {
        return TemporalFormatter.formatMonthDay(fields, TemporalFormatter.CalendarName.AUTO);
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

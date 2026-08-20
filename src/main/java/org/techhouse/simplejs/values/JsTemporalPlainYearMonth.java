package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;

/**
 * A JavaScript {@code Temporal.PlainYearMonth} value: a calendar year and month with no day-of-month
 * meaning of its own, "iso8601" calendar only (see the feature plan's scope-defining finding). The
 * wrapped {@link Iso8601Fields#day()} is the spec-mandated "reference ISO day" - present only so a
 * value round-trips exactly through {@code toString()}/{@code from()} (it defaults to 1 and is never
 * exposed as an accessor); calendar arithmetic (add/until/since) recomputes against a canonical day
 * of 1 rather than trusting this field, per {@code CalendarYearMonthFromFields}.
 */
public final class JsTemporalPlainYearMonth extends JsValue {
    private PropertyTable table;
    private final Iso8601Fields fields;

    public JsTemporalPlainYearMonth(Iso8601Fields fields) {
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

    public int referenceISODay() {
        return fields.day();
    }

    @Override
    public String toString() {
        return TemporalFormatter.formatYearMonth(fields);
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

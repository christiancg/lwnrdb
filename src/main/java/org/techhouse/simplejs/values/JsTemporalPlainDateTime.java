package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;

/**
 * A JavaScript {@code Temporal.PlainDateTime} value: a calendar date composed with a wall-clock
 * time, no time zone. Composes T3's {@link Iso8601Fields} with T2's {@link IsoTimeFields} -
 * "iso8601" calendar only (see the feature plan's scope-defining finding). All arithmetic/option
 * handling lives in {@code builtins/TemporalPlainDateTimeBuiltins}, mirroring the
 * {@link JsDate}/{@code DateBuiltins} split every other Temporal type already follows.
 */
public final class JsTemporalPlainDateTime extends JsValue {
    private PropertyTable table;
    private final Iso8601Fields date;
    private final IsoTimeFields time;

    public JsTemporalPlainDateTime(Iso8601Fields date, IsoTimeFields time) {
        this.date = date;
        this.time = time;
    }

    public Iso8601Fields date() {
        return date;
    }

    public IsoTimeFields time() {
        return time;
    }

    public int year() {
        return date.year();
    }

    public int month() {
        return date.month();
    }

    public int day() {
        return date.day();
    }

    public static int compare(JsTemporalPlainDateTime a, JsTemporalPlainDateTime b) {
        final var dateCmp = IsoCalendar.compareIsoDate(a.date, b.date);
        return dateCmp != 0 ? dateCmp : JsTemporalPlainTime.compareFields(a.time, b.time);
    }

    public boolean sameValue(JsTemporalPlainDateTime other) {
        return compare(this, other) == 0;
    }

    @Override
    public String toString() {
        return TemporalFormatter.formatDateTime(date, time, null, TemporalFormatter.CalendarName.AUTO);
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

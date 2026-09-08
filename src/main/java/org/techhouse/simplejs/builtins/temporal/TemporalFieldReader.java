package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.temporal.MonthCode.requireMonthCodeSyntax;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toIntegerField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toPositiveIntegerField;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeFrom.requireOffsetFieldSyntax;

import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// Reading a field is what records that the caller's `with` argument was not empty, so every
// read must go through this class: a field read directly off `fieldsLike` would not be counted.
public final class TemporalFieldReader {
    private final JsValue fieldsLike;
    private final InterpreterOps ops;
    private boolean sawAnyField;

    public TemporalFieldReader(JsValue fieldsLike, InterpreterOps ops) {
        this.fieldsLike = fieldsLike;
        this.ops = ops;
    }

    public int integer(String name, int fallback) {
        final var value = read(name);
        return value instanceof JsUndefined ? fallback : toIntegerField(value, name, ops);
    }

    public int positiveInteger(String name, int fallback) {
        final var value = read(name);
        return value instanceof JsUndefined ? fallback : toPositiveIntegerField(value, name, ops);
    }

    public Integer positiveIntegerOrNull(String name) {
        final var value = read(name);
        return value instanceof JsUndefined ? null : toPositiveIntegerField(value, name, ops);
    }

    public String monthCodeOrNull() {
        final var value = read("monthCode");
        return value instanceof JsUndefined ? null : requireMonthCodeSyntax(value, ops);
    }

    public String offsetOrNull() {
        final var value = read("offset");
        return value instanceof JsUndefined ? null : requireOffsetFieldSyntax(value, ops);
    }

    public boolean sawNoFields() {
        return !sawAnyField;
    }

    private JsValue read(String name) {
        final var value = ops.getMember(fieldsLike, new JsString(name));
        sawAnyField |= !(value instanceof JsUndefined);
        return value;
    }
}

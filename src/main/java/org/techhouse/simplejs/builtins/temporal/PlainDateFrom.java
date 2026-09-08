package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireValidCalendarField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requiredYearField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toPositiveIntegerField;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readOverflowOption;

import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class PlainDateFrom {
    public record UnresolvedDateFields(int year, Integer month, String monthCode, int day) {
    }

    public static JsTemporalPlainDate toPlainDate(JsValue item, InterpreterOps ops) {
        return toPlainDate(item, JsUndefined.getInstance(), ops);
    }

    public static JsTemporalPlainDate toPlainDate(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        if (item instanceof JsTemporalPlainDate pd) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainDate(pd.fields());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainDate wrapped) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainDate(wrapped.fields());
        }
        if (item instanceof JsTemporalPlainDateTime dt) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainDate(dt.date());
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainDate(zdt.isoFieldsAtLocal().date());
        }
        if (item instanceof JsString s) {
            final var parsed = TemporalParser.parseDate(s.getValue());
            if (parsed.calendar() != null) {
                TemporalCalendarIdentifier.requireBuiltinCalendar(parsed.calendar());
            }
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainDate(parsed.date());
        }
        if (InterpreterUtils.isObjectLike(item)) {
            final var fields = resolveDateFields(item, ops);
            final var overflow = readOverflowOption(optionsArg, ops);
            final var month = resolveMonthFromFields(fields.month(), fields.monthCode());
            return new JsTemporalPlainDate(IsoCalendar.regulateDate(fields.year(), month, fields.day(), overflow));
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.PlainDate");
    }

    public static UnresolvedDateFields resolveDateFields(JsValue obj, InterpreterOps ops) {
        requireValidCalendarField(obj, ops);
        final var day = requiredDayField(obj, ops);
        final var month = optionalMonthField(obj, ops);
        final var monthCode = optionalMonthCodeField(obj, ops);
        final var year = requiredYearField(obj, ops);
        return new UnresolvedDateFields(year, month, monthCode, day);
    }

    public static int requiredDayField(JsValue obj, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString("day"));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException("day is required");
        }
        return toPositiveIntegerField(value, "day", ops);
    }

    public static Integer optionalMonthField(JsValue obj, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString("month"));
        return value instanceof JsUndefined ? null : toPositiveIntegerField(value, "month", ops);
    }

    public static String optionalMonthCodeField(JsValue obj, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString("monthCode"));
        return value instanceof JsUndefined ? null : requireMonthCodeValue(value, ops);
    }

    public static String requireMonthCodeValue(JsValue value, InterpreterOps ops) {
        final var primitive = JsCoercion.toPrimitive(value, "string", ops);
        if (!(primitive instanceof JsString s)) {
            throw new TypeErrorException("monthCode must be a string");
        }
        requireMonthCodeSyntax(s.getValue());
        return s.getValue();
    }

    public static void requireMonthCodeSyntax(String code) {
        final var length = code.length();
        if ((length != 3 && length != 4) || code.charAt(0) != 'M' || !Character.isDigit(code.charAt(1))
                || !Character.isDigit(code.charAt(2)) || (length == 4 && code.charAt(3) != 'L')) {
            throw new RangeErrorException("Invalid monthCode: " + code);
        }
    }

    public static int monthCodeSuitabilityForIso(String code) {
        final var value = Integer.parseInt(code.substring(1, 3));
        if (code.length() == 4 || value < 1 || value > 12) {
            throw new RangeErrorException("monthCode is not valid for the iso8601 calendar: " + code);
        }
        return value;
    }

    public static int resolveMonthFromFields(Integer month, String monthCode) {
        if (monthCode != null) {
            final var fromCode = monthCodeSuitabilityForIso(monthCode);
            if (month != null && month != fromCode) {
                throw new RangeErrorException("month and monthCode are inconsistent");
            }
            return fromCode;
        }
        if (month != null) {
            return month;
        }
        throw new TypeErrorException("month or monthCode is required");
    }

    private PlainDateFrom() {
    }
}

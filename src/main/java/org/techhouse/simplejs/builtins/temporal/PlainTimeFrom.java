package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.TemporalPlainTimeBuiltins.ALPHABETICAL_FIELD_ORDER;
import static org.techhouse.simplejs.builtins.TemporalPlainTimeBuiltins.FIELD_NAMES;
import static org.techhouse.simplejs.builtins.TemporalPlainTimeBuiltins.member;
import static org.techhouse.simplejs.builtins.TemporalPlainTimeBuiltins.toIntegerWithTruncation;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.regulateTime;

import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class PlainTimeFrom {
    public static JsTemporalPlainTime toTemporalTime(JsValue item, InterpreterOps ops) {
        return toTemporalTime(item, RegulateOverflow.CONSTRAIN, ops);
    }

    public static JsTemporalPlainTime toTemporalTime(JsValue item, RegulateOverflow overflow, InterpreterOps ops) {
        if (item instanceof JsTemporalPlainTime time) {
            return new JsTemporalPlainTime(time.getFields());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainTime wrapped) {
            return new JsTemporalPlainTime(wrapped.getFields());
        }
        if (item instanceof JsTemporalPlainDateTime dt) {
            return new JsTemporalPlainTime(dt.time());
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            return new JsTemporalPlainTime(zdt.isoFieldsAtLocal().time());
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return fromTimeLikeObject(item, overflow, ops);
        }
        if (!(item instanceof JsString str)) {
            throw new TypeErrorException("Cannot convert to Temporal.PlainTime: expected a string or a property bag");
        }
        requireUnambiguousBareTimeString(str.getValue());
        return new JsTemporalPlainTime(TemporalParser.parseTime(str.getValue()).time());
    }

    public static void requireUnambiguousBareTimeString(String input) {
        if (input.isEmpty() || input.charAt(0) == 'T' || input.charAt(0) == 't') {
            return;
        }
        var core = input;
        final var bracket = core.indexOf('[');
        if (bracket >= 0) {
            core = core.substring(0, bracket);
        }
        if (isAmbiguousWithDateForm(core)) {
            throw new RangeErrorException("'" + input
                    + "' is ambiguous and requires a 'T' prefix to be parsed as a Temporal.PlainTime " + "string");
        }
    }

    public static boolean isAmbiguousWithDateForm(String core) {
        if (core.matches("\\d{4}-\\d{2}")) {
            return isValidMonth(core.substring(5, 7));
        }
        if (core.matches("\\d{6}")) {
            return isValidMonth(core.substring(4, 6));
        }
        if (core.matches("\\d{2}-\\d{2}")) {
            return isValidReducedMonthDay(core.substring(0, 2), core.substring(3, 5));
        }
        if (core.matches("\\d{4}")) {
            return isValidReducedMonthDay(core.substring(0, 2), core.substring(2, 4));
        }
        return false;
    }

    public static boolean isValidMonth(String digits) {
        final var month = Integer.parseInt(digits);
        return month >= 1 && month <= 12;
    }

    public static boolean isValidReducedMonthDay(String monthDigits, String dayDigits) {
        try {
            IsoCalendar.regulateDate(1972, Integer.parseInt(monthDigits), Integer.parseInt(dayDigits),
                    RegulateOverflow.REJECT);
            return true;
        } catch (RangeErrorException e) {
            return false;
        }
    }

    public static JsTemporalPlainTime fromTimeLikeObject(JsValue item, RegulateOverflow overflow, InterpreterOps ops) {
        final var values = readAlphabeticalTimeFields(item, ops);
        return new JsTemporalPlainTime(
                regulateTime(values[0], values[1], values[2], values[3], values[4], values[5], overflow));
    }

    public static int[] readAlphabeticalTimeFields(JsValue item, InterpreterOps ops) {
        final var values = new int[6];
        var any = false;
        for (final var idx : ALPHABETICAL_FIELD_ORDER) {
            final var value = member(item, FIELD_NAMES.get(idx), ops);
            if (!(value instanceof JsUndefined)) {
                any = true;
                values[idx] = (int) toIntegerWithTruncation(value, ops);
            }
        }
        if (!any) {
            throw new TypeErrorException("Invalid Temporal.PlainTime-like object: no recognized properties");
        }
        return values;
    }

    public static boolean isTemporalInstance(JsValue value) {
        return value instanceof JsTemporalPlainTime || value instanceof JsTemporalPlainDate
                || value instanceof JsTemporalPlainDateTime || value instanceof JsTemporalPlainMonthDay
                || value instanceof JsTemporalPlainYearMonth || value instanceof JsTemporalZonedDateTime
                || value instanceof JsTemporalInstant || value instanceof JsTemporalDuration;
    }

    public static JsValue from(List<JsValue> args, InterpreterOps ops) {
        final var item = arg(args, 0);
        final var optionsArg = arg(args, 1);
        if (item instanceof JsTemporalPlainTime time) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainTime(time.getFields());
        }
        if (item instanceof JsTemporalPlainDateTime dt) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainTime(dt.time());
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainTime(zdt.isoFieldsAtLocal().time());
        }
        if (InterpreterUtils.isObjectLike(item)) {
            final var values = readAlphabeticalTimeFields(item, ops);
            final var overflow = readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainTime(
                    regulateTime(values[0], values[1], values[2], values[3], values[4], values[5], overflow));
        }
        if (!(item instanceof JsString str)) {
            throw new TypeErrorException("Cannot convert to Temporal.PlainTime: expected a string or a property bag");
        }
        requireUnambiguousBareTimeString(str.getValue());
        final var time = TemporalParser.parseTime(str.getValue()).time();
        readOverflowOption(optionsArg, ops);
        return new JsTemporalPlainTime(time);
    }

    public static JsValue compareStatic(List<JsValue> args, InterpreterOps ops) {
        final var one = toTemporalTime(arg(args, 0), ops);
        final var two = toTemporalTime(arg(args, 1), ops);
        return new JsNumber(JsTemporalPlainTime.compare(one, two));
    }

    private static RegulateOverflow readOverflowOption(JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg instanceof JsUndefined) {
            return RegulateOverflow.CONSTRAIN;
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        final var value = member(optionsArg, "overflow", ops);
        return value instanceof JsUndefined
                ? RegulateOverflow.CONSTRAIN
                : RegulateOverflow.parse(JsCoercion.toStr(value, ops));
    }

    private PlainTimeFrom() {
    }
}

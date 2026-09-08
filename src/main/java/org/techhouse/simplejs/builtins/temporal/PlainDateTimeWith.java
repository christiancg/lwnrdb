package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.TemporalPlainDateTimeBuiltins.dateTime;
import static org.techhouse.simplejs.builtins.TemporalPlainDateTimeBuiltins.resolveMonthValue;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readOverflowOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.regulateTime;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.timeFromObjectRequireAny;
import static org.techhouse.simplejs.internal.temporal.TemporalBrand.isAnyTemporalValue;
import static org.techhouse.simplejs.internal.temporal.TemporalBrand.isTemporalWithCalendar;

import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class PlainDateTimeWith {
    public static JsValue with(JsTemporalPlainDateTime receiver, JsValue fieldsLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (isAnyTemporalValue(fieldsLike) || !InterpreterUtils.isObjectLike(fieldsLike)) {
            throw new TypeErrorException("Temporal.PlainDateTime.prototype.with argument must be a plain object");
        }
        rejectCalendarOrTimeZoneField(fieldsLike, ops);
        final var t = receiver.time();
        final var read = new TemporalFieldReader(fieldsLike, ops);
        final var day = read.positiveInteger("day", receiver.day());
        final var hour = read.integer("hour", t.hour());
        final var microsecond = read.integer("microsecond", t.microsecond());
        final var millisecond = read.integer("millisecond", t.millisecond());
        final var minute = read.integer("minute", t.minute());
        final var month = read.positiveIntegerOrNull("month");
        final var monthCode = read.monthCodeOrNull();
        final var nanosecond = read.integer("nanosecond", t.nanosecond());
        final var second = read.integer("second", t.second());
        final var year = read.integer("year", receiver.year());
        if (read.sawNoFields()) {
            throw new TypeErrorException("with() argument must contain at least one recognized property");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = resolveMonthValue(month, monthCode, receiver.month());
        final var date = IsoCalendar.regulateDate(year, resolvedMonth, day, overflow);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return dateTime(date, time);
    }

    public static void rejectCalendarOrTimeZoneField(JsValue fieldsLike, InterpreterOps ops) {
        if (!(ops.getMember(fieldsLike, new JsString("calendar")) instanceof JsUndefined)) {
            throw new TypeErrorException(
                    "Temporal.PlainDateTime.prototype.with argument must not have a calendar " + "property");
        }
        if (!(ops.getMember(fieldsLike, new JsString("timeZone")) instanceof JsUndefined)) {
            throw new TypeErrorException(
                    "Temporal.PlainDateTime.prototype.with argument must not have a timeZone " + "property");
        }
    }

    public static JsValue withCalendar(JsTemporalPlainDateTime receiver, JsValue calendarArg) {
        if (!isTemporalWithCalendar(calendarArg)) {
            if (!(calendarArg instanceof JsString s)) {
                throw new TypeErrorException("calendar must be a string");
            }
            TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated(s.getValue());
        }
        return dateTime(receiver.date(), receiver.time());
    }

    public static JsValue withPlainTime(JsTemporalPlainDateTime receiver, JsValue timeLike, InterpreterOps ops) {
        return dateTime(receiver.date(), toPlainTimeOrMidnight(timeLike, ops));
    }

    public static IsoTimeFields toPlainTimeOrMidnight(JsValue timeLike, InterpreterOps ops) {
        switch (timeLike) {
            case null -> {
                return new IsoTimeFields(0, 0, 0, 0, 0, 0);
            }
            case JsUndefined _ -> {
                return new IsoTimeFields(0, 0, 0, 0, 0, 0);
            }
            case JsTemporalPlainTime pt -> {
                return pt.getFields();
            }
            case JsString s -> {
                return TemporalParser.parseTime(s.getValue()).time();
            }
            default -> {
            }
        }
        if (InterpreterUtils.isObjectLike(timeLike)) {
            return timeFromObjectRequireAny(timeLike, ops);
        }
        throw new TypeErrorException("Temporal.PlainDateTime.prototype.withPlainTime requires a time-like value");
    }

    private PlainDateTimeWith() {
    }
}

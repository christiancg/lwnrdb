package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readDisambiguationOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readOverflowOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.regulateTime;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.timeFromObjectRequireAny;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeFrom.readOffsetOption;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeFrom.requiredIntegerField;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeFrom.resolveMonthSimple;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeFrom.resolveMonthValue;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.resolveToZoned;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.resolveToZonedWithOffset;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.zoneOf;
import static org.techhouse.simplejs.internal.temporal.TemporalBrand.isAnyTemporalValue;
import static org.techhouse.simplejs.internal.temporal.TemporalBrand.isTemporalWithCalendar;

import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.TemporalZonedDateTimeBuiltins.OffsetOption;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.Disambiguation;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.internal.temporal.TimeZoneStringParser;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ZonedDateTimeWith {
    public static JsValue with(JsTemporalZonedDateTime receiver, JsValue fieldsLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(fieldsLike) || isAnyTemporalValue(fieldsLike)) {
            throw new TypeErrorException("Temporal.ZonedDateTime.prototype.with argument must be a plain object");
        }
        rejectCalendarOrTimeZoneField(fieldsLike, ops);
        final var fields = receiver.isoFieldsAtLocal();
        final var t = fields.time();
        final var read = new TemporalFieldReader(fieldsLike, ops);
        final var day = read.positiveInteger("day", fields.date().day());
        final var hour = read.integer("hour", t.hour());
        final var microsecond = read.integer("microsecond", t.microsecond());
        final var millisecond = read.integer("millisecond", t.millisecond());
        final var minute = read.integer("minute", t.minute());
        final var month = read.positiveIntegerOrNull("month");
        final var monthCode = read.monthCodeOrNull();
        final var nanosecond = read.integer("nanosecond", t.nanosecond());
        final var offsetText = read.offsetOrNull();
        final var second = read.integer("second", t.second());
        final var year = read.integer("year", fields.date().year());
        if (read.sawNoFields()) {
            throw new TypeErrorException("with() argument must contain at least one recognized property");
        }
        final var disambiguation = readDisambiguationOption(optionsArg, ops);
        final var offsetOption = readOffsetOption(optionsArg, ops, OffsetOption.PREFER);
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = resolveMonthValue(month, monthCode, fields.date().month());
        final var newDate = IsoCalendar.regulateDate(year, resolvedMonth, day, overflow);
        final var newTime = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return resolveToZonedWithOffset(newDate, newTime, receiver.zone(), receiver.timeZoneId(), offsetText,
                disambiguation, offsetOption);
    }

    public static void rejectCalendarOrTimeZoneField(JsValue fieldsLike, InterpreterOps ops) {
        if (!(ops.getMember(fieldsLike, new JsString("calendar")) instanceof JsUndefined)) {
            throw new TypeErrorException(
                    "Temporal.ZonedDateTime.prototype.with argument must not have a calendar property");
        }
        if (!(ops.getMember(fieldsLike, new JsString("timeZone")) instanceof JsUndefined)) {
            throw new TypeErrorException(
                    "Temporal.ZonedDateTime.prototype.with argument must not have a timeZone property");
        }
    }

    public static JsValue withCalendar(JsTemporalZonedDateTime receiver, JsValue calendarArg) {
        if (!isTemporalWithCalendar(calendarArg)) {
            if (!(calendarArg instanceof JsString s)) {
                throw new TypeErrorException("calendar must be a string");
            }
            TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated(s.getValue());
        }
        return new JsTemporalZonedDateTime(receiver.epochSecondsPart(), receiver.nanoAdjustment(), receiver.zone(),
                receiver.timeZoneId());
    }

    public static JsValue withTimeZone(JsTemporalZonedDateTime receiver, JsValue timeZoneArg) {
        if (!(timeZoneArg instanceof JsString s)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        final var timeZoneId = TimeZoneStringParser.parseTimeZoneIdentifierFlexible(s.getValue());
        return new JsTemporalZonedDateTime(receiver.epochSecondsPart(), receiver.nanoAdjustment(), zoneOf(timeZoneId),
                timeZoneId);
    }

    public static JsValue withPlainDate(JsTemporalZonedDateTime receiver, JsValue dateArg, InterpreterOps ops) {
        final var date = toDateFields(dateArg, ops);
        final var time = receiver.isoFieldsAtLocal().time();
        return resolveToZoned(date, time, receiver.zone(), receiver.timeZoneId(), Disambiguation.COMPATIBLE);
    }

    public static Iso8601Fields toDateFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalPlainDate pd) {
            return pd.fields();
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainDate wrapped) {
            return wrapped.fields();
        }
        if (value instanceof JsString s) {
            return TemporalParser.parseDate(s.getValue()).date();
        }
        if (InterpreterUtils.isObjectLike(value)) {
            final var year = requiredIntegerField(value, "year", ops);
            final var month = resolveMonthSimple(value, ops);
            final var day = requiredIntegerField(value, "day", ops);
            return IsoCalendar.regulateDate(year, month, day, RegulateOverflow.CONSTRAIN);
        }
        throw new TypeErrorException("Temporal.ZonedDateTime.prototype.withPlainDate requires a date-like value");
    }

    public static JsValue withPlainTime(JsTemporalZonedDateTime receiver, JsValue timeArg, InterpreterOps ops) {
        final var time = toPlainTimeOrMidnight(timeArg, ops);
        final var date = receiver.isoFieldsAtLocal().date();
        return resolveToZoned(date, time, receiver.zone(), receiver.timeZoneId(), Disambiguation.COMPATIBLE);
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
            case JsObject wrapper when wrapper.getPrimitive() instanceof JsTemporalPlainTime wrapped -> {
                return wrapped.getFields();
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
        throw new TypeErrorException("Temporal.ZonedDateTime.prototype.withPlainTime requires a time-like value");
    }

    private ZonedDateTimeWith() {
    }
}

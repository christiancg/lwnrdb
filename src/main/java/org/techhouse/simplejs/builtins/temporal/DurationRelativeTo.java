package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MIDNIGHT;

import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RelativeDurationMath;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.internal.temporal.TimeZoneStringParser;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class DurationRelativeTo {
    public static RelativeDurationMath.Anchor toRelativeToAnchor(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalZonedDateTime zdt) {
            final var f = zdt.isoFieldsAtLocal();
            return RelativeDurationMath.Anchor.zoned(f.date(), f.time(), zdt.zone());
        }
        if (value instanceof JsTemporalPlainDateTime pdt) {
            return RelativeDurationMath.Anchor.plain(pdt.date(), pdt.time());
        }
        if (value instanceof JsTemporalPlainDate pd) {
            return RelativeDurationMath.Anchor.plain(pd.fields(), MIDNIGHT);
        }
        if (value instanceof JsString s) {
            return parseRelativeToString(s.getValue());
        }
        if (InterpreterUtils.isObjectLike(value)) {
            return relativeToFromFields(value, ops);
        }
        throw new TypeErrorException("relativeTo must be a Temporal.PlainDate, Temporal.PlainDateTime, "
                + "Temporal.ZonedDateTime, an ISO 8601 string, or a fields-like object");
    }

    public static RelativeDurationMath.Anchor parseRelativeToString(String text) {
        final var parsed = TemporalParser.parseRelativeToString(text);
        if (parsed.calendar() != null) {
            TemporalCalendarIdentifier.requireBuiltinCalendar(parsed.calendar());
        }
        final var date = parsed.date();
        final var time = parsed.time() != null ? parsed.time() : MIDNIGHT;
        if (parsed.timeZoneId() == null) {
            if ("Z".equalsIgnoreCase(parsed.offset())) {
                throw new RangeErrorException(
                        "relativeTo string with a UTC designator requires a bracketed time zone annotation: " + text);
            }
            return RelativeDurationMath.Anchor.plain(date, time);
        }
        final var timeZoneId = TimeZoneStringParser.parseTimeZoneIdentifier(parsed.timeZoneId());
        final var zone = ZonedDateTimeZones.zoneOf(timeZoneId);
        if (parsed.offset() != null && !"Z".equalsIgnoreCase(parsed.offset())) {
            requireMatchingOffset(date, time, zone, parsed.offset(), text);
        }
        return RelativeDurationMath.Anchor.zoned(date, time, zone);
    }

    public static void requireMatchingOffset(Iso8601Fields date, IsoTimeFields time, java.time.ZoneId zone,
            String offsetText, String source) {
        var normalized = offsetText;
        final var dot = normalized.indexOf('.');
        final var comma = normalized.indexOf(',');
        final var fractionStart = dot < 0 ? comma : (comma < 0 ? dot : Math.min(dot, comma));
        if (fractionStart >= 0) {
            normalized = normalized.substring(0, fractionStart);
        }
        final var givenSeconds = parseOffsetSeconds(normalized);
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final java.time.LocalDateTime local;
        try {
            local = java.time.LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(),
                    time.second(), nanoOfSecond);
        } catch (java.time.DateTimeException e) {
            throw new RangeErrorException("Invalid date/time in relativeTo: " + e.getMessage());
        }
        final var actual = zone.getRules().getOffset(local);
        if (givenSeconds != actual.getTotalSeconds()) {
            throw new RangeErrorException(
                    "relativeTo string offset " + offsetText + " does not match time zone " + zone + ": " + source);
        }
    }

    public static long parseOffsetSeconds(String text) {
        if (text.length() < 3 || (text.charAt(0) != '+' && text.charAt(0) != '-')) {
            throw new RangeErrorException("Invalid UTC offset: " + text);
        }
        final var sign = text.charAt(0) == '-' ? -1 : 1;
        final var body = text.substring(1);
        final int hours;
        final int minutes;
        final int seconds;
        if (body.contains(":")) {
            final var parts = body.split(":", -1);
            if (parts.length < 2 || parts.length > 3 || !allTwoDigits(parts)) {
                throw new RangeErrorException("Invalid UTC offset: " + text);
            }
            hours = Integer.parseInt(parts[0]);
            minutes = Integer.parseInt(parts[1]);
            seconds = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
        } else {
            if ((body.length() != 2 && body.length() != 4 && body.length() != 6) || !body.matches("\\d+")) {
                throw new RangeErrorException("Invalid UTC offset: " + text);
            }
            hours = Integer.parseInt(body.substring(0, 2));
            minutes = body.length() >= 4 ? Integer.parseInt(body.substring(2, 4)) : 0;
            seconds = body.length() >= 6 ? Integer.parseInt(body.substring(4, 6)) : 0;
        }
        final var totalSeconds = hours * 3600L + minutes * 60L + seconds;
        if (totalSeconds >= 86400L) {
            throw new RangeErrorException("Invalid UTC offset: " + text);
        }
        return sign * totalSeconds;
    }

    public static boolean allTwoDigits(String[] parts) {
        for (final var part : parts) {
            if (part.length() != 2 || !part.matches("\\d+")) {
                return false;
            }
        }
        return true;
    }

    private static void requireValidCalendarField(JsValue obj, InterpreterOps ops) {
        final var calendarRaw = ops.getMember(obj, new JsString("calendar"));
        if (calendarRaw instanceof JsUndefined || calendarRaw instanceof JsTemporalPlainDate
                || calendarRaw instanceof JsTemporalPlainDateTime || calendarRaw instanceof JsTemporalPlainMonthDay
                || calendarRaw instanceof JsTemporalPlainYearMonth || calendarRaw instanceof JsTemporalZonedDateTime) {
            return;
        }
        if (!(calendarRaw instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated(s.getValue());
    }

    public static RelativeDurationMath.Anchor relativeToFromFields(JsValue obj, InterpreterOps ops) {
        requireValidCalendarField(obj, ops);
        final var day = requiredRelativeToField(obj, "day", ops);
        final var hour = relativeToFieldOrDefault(obj, "hour", 0, ops);
        final var microsecond = relativeToFieldOrDefault(obj, "microsecond", 0, ops);
        final var millisecond = relativeToFieldOrDefault(obj, "millisecond", 0, ops);
        final var minute = relativeToFieldOrDefault(obj, "minute", 0, ops);
        final var month = resolveRelativeToMonth(obj, ops);
        final var nanosecond = relativeToFieldOrDefault(obj, "nanosecond", 0, ops);
        final var offset = relativeToOffsetField(obj, ops);
        final var secondRaw = relativeToFieldOrDefault(obj, "second", 0, ops);
        final var second = secondRaw == 60 ? 59 : secondRaw;
        final var timeZoneRaw = ops.getMember(obj, new JsString("timeZone"));
        final var year = requiredRelativeToField(obj, "year", ops);
        final var date = IsoCalendar.regulateDate(year, month, day, RegulateOverflow.CONSTRAIN);
        final var time = new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
        if (timeZoneRaw instanceof JsUndefined) {
            return RelativeDurationMath.Anchor.plain(date, time);
        }
        if (!(timeZoneRaw instanceof JsString s)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        final var timeZoneId = TimeZoneStringParser.parseTimeZoneIdentifierFlexible(s.getValue());
        final var zone = ZonedDateTimeZones.zoneOf(timeZoneId);
        if (offset != null && !"Z".equalsIgnoreCase(offset)) {
            requireMatchingOffset(date, time, zone, offset, "relativeTo property bag");
        }
        return RelativeDurationMath.Anchor.zoned(date, time, zone);
    }

    public static String relativeToOffsetField(JsValue obj, InterpreterOps ops) {
        final var offsetRaw = ops.getMember(obj, new JsString("offset"));
        if (offsetRaw instanceof JsUndefined) {
            return null;
        }
        if (offsetRaw instanceof JsString s) {
            return s.getValue();
        }
        if (InterpreterUtils.isObjectLike(offsetRaw)) {
            return JsCoercion.toStr(offsetRaw, ops);
        }
        throw new TypeErrorException("offset must be a string");
    }

    public static int requiredRelativeToField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required in a relativeTo fields object");
        }
        return relativeToIntegerField(value, name, ops);
    }

    public static int relativeToFieldOrDefault(JsValue obj, String name, int defaultValue, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        return value instanceof JsUndefined ? defaultValue : relativeToIntegerField(value, name, ops);
    }

    public static int resolveRelativeToMonth(JsValue obj, InterpreterOps ops) {
        final var monthValue = ops.getMember(obj, new JsString("month"));
        final Integer monthNumeric = monthValue instanceof JsUndefined
                ? null
                : relativeToIntegerField(monthValue, "month", ops);
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        if (!(monthCodeValue instanceof JsUndefined)) {
            final var resolved = parseRelativeToMonthCode(JsCoercion.toStr(monthCodeValue, ops));
            if (monthNumeric != null && monthNumeric != resolved) {
                throw new RangeErrorException("month and monthCode are inconsistent");
            }
            return resolved;
        }
        if (monthNumeric == null) {
            throw new TypeErrorException("month or monthCode is required in a relativeTo fields object");
        }
        return monthNumeric;
    }

    public static int parseRelativeToMonthCode(String code) {
        if (code.length() == 3 && code.charAt(0) == 'M') {
            try {
                final var value = Integer.parseInt(code.substring(1));
                if (value >= 1 && value <= 12) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        throw new RangeErrorException("Invalid monthCode: " + code);
    }

    public static long roundingIncrementValue(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException("roundingIncrement must be a finite integer, got " + number);
        }
        return (long) (number < 0 ? Math.ceil(number) : Math.floor(number));
    }

    public static int relativeToIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException(name + " must be a finite integer, got " + number);
        }
        final var truncated = number < 0 ? Math.ceil(number) : Math.floor(number);
        return (int) truncated;
    }

    private DurationRelativeTo() {
    }
}

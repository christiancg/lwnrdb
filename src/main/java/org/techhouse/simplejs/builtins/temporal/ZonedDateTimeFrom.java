package org.techhouse.simplejs.builtins.temporal;

import static org.techhouse.simplejs.builtins.temporal.MonthCode.monthCodeNumericValue;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.requireMonthCodeSyntax;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireValidCalendarField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toIntegerField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toPositiveIntegerField;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.optionOrUndefined;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readDisambiguationOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readOverflowOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.regulateTime;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.epochNanosOf;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.interpretOffset;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.resolveToZonedWithOffset;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.toZoneOffset;
import static org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones.zoneOf;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.TemporalZonedDateTimeBuiltins.OffsetOption;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.internal.temporal.TimeZoneStringParser;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ZonedDateTimeFrom {
    public static JsTemporalZonedDateTime toZonedDateTime(JsValue item, InterpreterOps ops) {
        return toZonedDateTime(item, JsUndefined.getInstance(), ops);
    }

    public static JsTemporalZonedDateTime toZonedDateTime(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        if (item instanceof JsTemporalZonedDateTime zdt) {
            readCloneOptions(optionsArg, ops);
            return new JsTemporalZonedDateTime(zdt.epochSecondsPart(), zdt.nanoAdjustment(), zdt.zone(),
                    zdt.timeZoneId());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalZonedDateTime wrapped) {
            readCloneOptions(optionsArg, ops);
            return new JsTemporalZonedDateTime(wrapped.epochSecondsPart(), wrapped.nanoAdjustment(), wrapped.zone(),
                    wrapped.timeZoneId());
        }
        if (item instanceof JsString s) {
            return fromIsoString(s.getValue(), optionsArg, ops);
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return zonedDateTimeFromFields(item, optionsArg, ops);
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.ZonedDateTime");
    }

    public static void readCloneOptions(JsValue optionsArg, InterpreterOps ops) {
        readDisambiguationOption(optionsArg, ops);
        readOffsetOption(optionsArg, ops, OffsetOption.REJECT);
        readOverflowOption(optionsArg, ops);
    }

    public static JsTemporalZonedDateTime fromIsoString(String text, JsValue optionsArg, InterpreterOps ops) {
        final var parsed = TemporalParser.parseZonedDateTime(text);
        if (parsed.calendar() != null) {
            TemporalCalendarIdentifier.requireBuiltinCalendar(parsed.calendar());
        }
        final var timeZoneId = TimeZoneStringParser.parseTimeZoneIdentifier(parsed.timeZoneId());
        final var zone = zoneOf(timeZoneId);
        final var date = parsed.date();
        final var time = parsed.time() != null ? parsed.time() : new IsoTimeFields(0, 0, 0, 0, 0, 0);
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final LocalDateTime local;
        try {
            local = LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(), time.second(),
                    nanoOfSecond);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid Temporal.ZonedDateTime string: " + e.getMessage());
        }
        final var disambiguation = readDisambiguationOption(optionsArg, ops);
        final var offsetOption = readOffsetOption(optionsArg, ops, OffsetOption.REJECT);
        readOverflowOption(optionsArg, ops);
        final var resolved = "Z".equals(parsed.offset())
                ? local.atZone(ZoneOffset.UTC).withZoneSameInstant(zone)
                : interpretOffset(local, zone, parsed.offset(), disambiguation, offsetOption);
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(resolved));
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(resolved, timeZoneId);
    }

    public static JsTemporalZonedDateTime zonedDateTimeFromFields(JsValue obj, JsValue optionsArg, InterpreterOps ops) {
        requireValidCalendarField(obj, ops);
        final var dayValue = ops.getMember(obj, new JsString("day"));
        if (dayValue instanceof JsUndefined) {
            throw new TypeErrorException("day is required");
        }
        final var day = toPositiveIntegerField(dayValue, "day", ops);
        final var hour = fieldOrDefault(obj, "hour", 0, ops);
        final var microsecond = fieldOrDefault(obj, "microsecond", 0, ops);
        final var millisecond = fieldOrDefault(obj, "millisecond", 0, ops);
        final var minute = fieldOrDefault(obj, "minute", 0, ops);
        final var monthValue = ops.getMember(obj, new JsString("month"));
        final Integer month = monthValue instanceof JsUndefined
                ? null
                : toPositiveIntegerField(monthValue, "month", ops);
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        final var monthCode = monthCodeValue instanceof JsUndefined
                ? null
                : requireMonthCodeSyntax(monthCodeValue, ops);
        if (month == null && monthCode == null) {
            throw new TypeErrorException("month or monthCode is required");
        }
        final var nanosecond = fieldOrDefault(obj, "nanosecond", 0, ops);
        final var offsetValue = ops.getMember(obj, new JsString("offset"));
        final var offsetText = offsetValue instanceof JsUndefined ? null : requireOffsetFieldSyntax(offsetValue, ops);
        final var second = fieldOrDefault(obj, "second", 0, ops);
        final var timeZoneId = requireTimeZoneField(obj, ops);
        final var year = requiredIntegerField(obj, "year", ops);
        final var disambiguation = readDisambiguationOption(optionsArg, ops);
        final var offsetOption = readOffsetOption(optionsArg, ops, OffsetOption.REJECT);
        final var overflow = readOverflowOption(optionsArg, ops);
        final var zone = zoneOf(timeZoneId);
        final var resolvedMonth = resolveMonthValue(month, monthCode);
        final var date = IsoCalendar.regulateDate(year, resolvedMonth, day, overflow);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return resolveToZonedWithOffset(date, time, zone, timeZoneId, offsetText, disambiguation, offsetOption);
    }

    public static int requiredIntegerField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required");
        }
        return toIntegerField(value, name, ops);
    }

    public static String requireTimeZoneField(JsValue obj, InterpreterOps ops) {
        final var timeZoneRaw = ops.getMember(obj, new JsString("timeZone"));
        if (timeZoneRaw instanceof JsUndefined) {
            throw new TypeErrorException("timeZone is required");
        }
        if (timeZoneRaw instanceof JsTemporalZonedDateTime zdt) {
            return zdt.timeZoneId();
        }
        if (timeZoneRaw instanceof JsObject wrapper
                && wrapper.getPrimitive() instanceof JsTemporalZonedDateTime wrapped) {
            return wrapped.timeZoneId();
        }
        if (!(timeZoneRaw instanceof JsString timeZoneStr)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        return TimeZoneStringParser.parseTimeZoneIdentifierFlexible(timeZoneStr.getValue());
    }

    public static int resolveMonthValue(Integer month, String monthCode) {
        if (monthCode == null) {
            return month;
        }
        final var resolved = monthCodeNumericValue(monthCode);
        if (month != null && month != resolved) {
            throw new RangeErrorException("month and monthCode are inconsistent");
        }
        return resolved;
    }

    public static int resolveMonthValue(Integer month, String monthCode, int defaultMonth) {
        if (month == null && monthCode == null) {
            return defaultMonth;
        }
        return resolveMonthValue(month, monthCode);
    }

    public static int resolveMonthSimple(JsValue obj, InterpreterOps ops) {
        final var monthValue = ops.getMember(obj, new JsString("month"));
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        final Integer month = monthValue instanceof JsUndefined ? null : toIntegerField(monthValue, "month", ops);
        final String monthCode = monthCodeValue instanceof JsUndefined
                ? null
                : requireMonthCodeSyntax(monthCodeValue, ops);
        if (month == null && monthCode == null) {
            throw new TypeErrorException("month or monthCode is required");
        }
        return resolveMonthValue(month, monthCode);
    }

    public static int fieldOrDefault(JsValue obj, String name, int defaultValue, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        return value instanceof JsUndefined ? defaultValue : toIntegerField(value, name, ops);
    }

    public static OffsetOption readOffsetOption(JsValue optionsArg, InterpreterOps ops, OffsetOption fallback) {
        final var value = optionOrUndefined(optionsArg, "offset", ops);
        return value instanceof JsUndefined ? fallback : OffsetOption.parse(JsCoercion.toStr(value, ops));
    }

    public static String requireOffsetFieldSyntax(JsValue value, InterpreterOps ops) {
        final var primitive = JsCoercion.toPrimitive(value, "string", ops);
        if (!(primitive instanceof JsString s)) {
            throw new TypeErrorException("offset must be a string");
        }
        final var text = s.getValue();
        toZoneOffset(text);
        return text;
    }

    public static TemporalFormatter.TimeZoneNameOption readTimeZoneNameOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "timeZoneName", ops);
        return value instanceof JsUndefined
                ? TemporalFormatter.TimeZoneNameOption.AUTO
                : TemporalFormatter.TimeZoneNameOption.parse(JsCoercion.toStr(value, ops));
    }

    public static TemporalFormatter.OffsetOption readOffsetDisplayOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "offset", ops);
        return value instanceof JsUndefined
                ? TemporalFormatter.OffsetOption.AUTO
                : TemporalFormatter.OffsetOption.parse(JsCoercion.toStr(value, ops));
    }

    private ZonedDateTimeFrom() {
    }
}

package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTargetOrSubclassInstance;
import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.monthCode;
import static org.techhouse.simplejs.builtins.temporal.PlainDateFrom.requireMonthCodeValue;
import static org.techhouse.simplejs.builtins.temporal.PlainDateFrom.resolveMonthFromFields;
import static org.techhouse.simplejs.builtins.temporal.PlainDateFrom.toPlainDate;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.orZeroDuration;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.readDurationField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireCalendarString;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toIntegerField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toPositiveIntegerField;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.negateRoundingMode;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.optionOrUndefined;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readCalendarNameOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readOverflowOption;
import static org.techhouse.simplejs.internal.temporal.TemporalAccessors.installGetter;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MIDNIGHT;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MIN_ISO_DATE;

import java.util.List;
import org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.Disambiguation;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.DurationStringParser;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RelativeDurationMath;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.internal.temporal.TimeZoneStringParser;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
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

public final class TemporalPlainDateBuiltins {
    public static final List<String> NAMES = List.of("with", "withCalendar", "add", "subtract", "until", "since",
            "equals", "toPlainYearMonth", "toPlainMonthDay", "toPlainDateTime", "toZonedDateTime", "toString", "toJSON",
            "toLocaleString", "getISOFields", "valueOf");

    private TemporalPlainDateBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainDate", (thisArg, args) -> {
            requireNewTargetOrSubclassInstance("Temporal.PlainDate", thisArg);
            return withNewTargetPrototype(construct(args, ops), ops);
        });
        ctor.setLength(3);
        final var from = new JsNativeFunction("from", (_, args) -> toPlainDate(arg(args, 0), arg(args, 1), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare", (_, args) -> new JsNumber(IsoCalendar
                .compareIsoDate(toPlainDate(arg(args, 0), ops).fields(), toPlainDate(arg(args, 1), ops).fields())));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        return ctor;
    }

    public static JsValue getMethod(JsTemporalPlainDate receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
            case "withCalendar" ->
                new JsNativeFunction("withCalendar", (_, args) -> withCalendar(receiver, arg(args, 0)));
            case "add" -> new JsNativeFunction("add", (_, args) -> add(receiver, arg(args, 0), arg(args, 1), ops));
            case "subtract" ->
                new JsNativeFunction("subtract", (_, args) -> subtract(receiver, arg(args, 0), arg(args, 1), ops));
            case "until" ->
                new JsNativeFunction("until", (_, args) -> until(receiver, arg(args, 0), arg(args, 1), ops));
            case "since" ->
                new JsNativeFunction("since", (_, args) -> since(receiver, arg(args, 0), arg(args, 1), ops));
            case "equals" -> new JsNativeFunction("equals", (_, args) -> equalsMethod(receiver, arg(args, 0), ops));
            case "toPlainYearMonth" -> new JsNativeFunction("toPlainYearMonth", (_, _) -> toPlainYearMonth(receiver));
            case "toPlainMonthDay" -> new JsNativeFunction("toPlainMonthDay", (_, _) -> toPlainMonthDay(receiver));
            case "toPlainDateTime" ->
                new JsNativeFunction("toPlainDateTime", (_, args) -> toPlainDateTime(receiver, arg(args, 0), ops));
            case "toZonedDateTime" ->
                new JsNativeFunction("toZonedDateTime", (_, args) -> toZonedDateTime(receiver, arg(args, 0), ops));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "getISOFields" -> new JsNativeFunction("getISOFields", (_, _) -> getISOFields(receiver));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException(
                        "Cannot convert a Temporal.PlainDate to a primitive value with valueOf; use compare() or "
                                + "equals() instead");
            });
            default -> null;
        };
    }

    public static void installAccessors(JsObject proto) {
        installGetter(proto, "year", receiver -> new JsNumber(requireReceiver(receiver, "year").year()));
        installGetter(proto, "month", receiver -> new JsNumber(requireReceiver(receiver, "month").month()));
        installGetter(proto, "monthCode",
                receiver -> new JsString(monthCode(requireReceiver(receiver, "monthCode").month())));
        installGetter(proto, "day", receiver -> new JsNumber(requireReceiver(receiver, "day").day()));
        installGetter(proto, "dayOfWeek",
                receiver -> new JsNumber(IsoCalendar.dayOfWeek(requireReceiver(receiver, "dayOfWeek").fields())));
        installGetter(proto, "dayOfYear",
                receiver -> new JsNumber(IsoCalendar.dayOfYear(requireReceiver(receiver, "dayOfYear").fields())));
        installGetter(proto, "weekOfYear",
                receiver -> new JsNumber(IsoCalendar.weekOfYear(requireReceiver(receiver, "weekOfYear").fields())));
        installGetter(proto, "yearOfWeek",
                receiver -> new JsNumber(IsoCalendar.yearOfWeek(requireReceiver(receiver, "yearOfWeek").fields())));
        installGetter(proto, "daysInWeek", receiver -> {
            requireReceiver(receiver, "daysInWeek");
            return new JsNumber(7);
        });
        installGetter(proto, "daysInMonth", receiver -> {
            final var date = requireReceiver(receiver, "daysInMonth");
            return new JsNumber(IsoCalendar.daysInMonth(date.year(), date.month()));
        });
        installGetter(proto, "daysInYear", receiver -> {
            final var date = requireReceiver(receiver, "daysInYear");
            return new JsNumber(IsoCalendar.daysInYear(date.year()));
        });
        installGetter(proto, "monthsInYear", receiver -> {
            requireReceiver(receiver, "monthsInYear");
            return new JsNumber(12);
        });
        installGetter(proto, "inLeapYear", receiver -> {
            final var date = requireReceiver(receiver, "inLeapYear");
            return JsBoolean.of(IsoCalendar.isLeapYear(date.year()));
        });
        installGetter(proto, "calendarId", receiver -> {
            requireReceiver(receiver, "calendarId");
            return new JsString("iso8601");
        });
        installGetter(proto, "era", receiver -> {
            requireReceiver(receiver, "era");
            return JsUndefined.getInstance();
        });
        installGetter(proto, "eraYear", receiver -> {
            requireReceiver(receiver, "eraYear");
            return JsUndefined.getInstance();
        });
    }

    private static JsTemporalPlainDate requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalPlainDate date) {
            return date;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainDate wrapped) {
            return wrapped;
        }
        throw new TypeErrorException("Temporal.PlainDate.prototype." + method + " called on an incompatible receiver");
    }

    private static JsTemporalPlainDate construct(List<JsValue> args, InterpreterOps ops) {
        final var year = toIntegerField(arg(args, 0), "year", ops);
        final var month = toIntegerField(arg(args, 1), "month", ops);
        final var day = toIntegerField(arg(args, 2), "day", ops);
        final var calendarArg = arg(args, 3);
        if (!(calendarArg instanceof JsUndefined)) {
            requireCalendarString(calendarArg);
        }
        return new JsTemporalPlainDate(IsoCalendar.regulateDate(year, month, day, RegulateOverflow.REJECT));
    }

    private static String readRawStringOption(JsValue optionsArg, String key, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, key, ops);
        return value instanceof JsUndefined ? null : JsCoercion.toStr(value, ops);
    }

    private static Long readRawIncrementOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "roundingIncrement", ops);
        return value instanceof JsUndefined ? null : (long) toIntegerField(value, "roundingIncrement", ops);
    }

    private static long validateIncrement(Long raw) {
        final var value = raw == null ? 1L : raw;
        if (value < 1 || value > 1_000_000_000L) {
            throw new RangeErrorException("roundingIncrement out of range: " + value);
        }
        return value;
    }

    private static Unit requireDateUnit(String raw, String optionName) {
        final var unit = Unit.parseTemporalUnit(raw);
        if (unit.ordinal() > Unit.DAY.ordinal()) {
            throw new RangeErrorException(
                    optionName + " must be one of \"year\", \"month\", \"week\" or \"day\" for Temporal.PlainDate, "
                            + "got: " + raw);
        }
        return unit;
    }

    private static JsValue with(JsTemporalPlainDate receiver, JsValue dateLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(dateLike)) {
            throw new TypeErrorException("Temporal.PlainDate.prototype.with argument must be an object");
        }
        rejectTemporalLikeObject(dateLike);
        rejectCalendarOrTimeZoneProperty(dateLike, ops);
        final var dayRaw = ops.getMember(dateLike, new JsString("day"));
        final var dayPresent = !(dayRaw instanceof JsUndefined);
        final var day = dayPresent ? toPositiveIntegerField(dayRaw, "day", ops) : receiver.day();
        final var monthRaw = ops.getMember(dateLike, new JsString("month"));
        final var monthPresent = !(monthRaw instanceof JsUndefined);
        final Integer month = monthPresent ? toPositiveIntegerField(monthRaw, "month", ops) : null;
        final var monthCodeRaw = ops.getMember(dateLike, new JsString("monthCode"));
        final var monthCodePresent = !(monthCodeRaw instanceof JsUndefined);
        final String monthCode = monthCodePresent ? requireMonthCodeValue(monthCodeRaw, ops) : null;
        final var yearRaw = ops.getMember(dateLike, new JsString("year"));
        final var yearPresent = !(yearRaw instanceof JsUndefined);
        final var year = yearPresent ? toIntegerField(yearRaw, "year", ops) : receiver.year();
        if (!dayPresent && !monthPresent && !monthCodePresent && !yearPresent) {
            throw new TypeErrorException("with() argument must have at least one recognized date field");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = monthPresent || monthCodePresent
                ? resolveMonthFromFields(month, monthCode)
                : receiver.month();
        return new JsTemporalPlainDate(IsoCalendar.regulateDate(year, resolvedMonth, day, overflow));
    }

    private static void rejectTemporalLikeObject(JsValue value) {
        if (value instanceof JsTemporalPlainDate || value instanceof JsTemporalPlainDateTime
                || value instanceof JsTemporalPlainMonthDay || value instanceof JsTemporalPlainTime
                || value instanceof JsTemporalPlainYearMonth || value instanceof JsTemporalZonedDateTime
                || value instanceof JsTemporalDuration || value instanceof JsTemporalInstant) {
            throw new TypeErrorException("Temporal.PlainDate.prototype.with argument must not be a Temporal object");
        }
    }

    private static void rejectCalendarOrTimeZoneProperty(JsValue value, InterpreterOps ops) {
        final var calendarValue = ops.getMember(value, new JsString("calendar"));
        if (!(calendarValue instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a calendar property");
        }
        final var timeZoneValue = ops.getMember(value, new JsString("timeZone"));
        if (!(timeZoneValue instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a timeZone property");
        }
    }

    private static JsValue withCalendar(JsTemporalPlainDate receiver, JsValue calendarArg) {
        if (!(calendarArg instanceof JsTemporalPlainDate || calendarArg instanceof JsTemporalPlainDateTime
                || calendarArg instanceof JsTemporalPlainMonthDay || calendarArg instanceof JsTemporalPlainYearMonth
                || calendarArg instanceof JsTemporalZonedDateTime)) {
            if (!(calendarArg instanceof JsString s)) {
                throw new TypeErrorException("calendar must be a string");
            }
            TemporalCalendarIdentifier.requireBuiltinCalendarOrAnnotated(s.getValue());
        }
        return new JsTemporalPlainDate(receiver.fields());
    }

    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsString s) {
            return DurationStringParser.parseDuration(s.getValue());
        }
        if (!InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("Invalid Temporal.Duration-like value");
        }
        final var days = readDurationField(value, "days", ops);
        final var hours = readDurationField(value, "hours", ops);
        final var microseconds = readDurationField(value, "microseconds", ops);
        final var milliseconds = readDurationField(value, "milliseconds", ops);
        final var minutes = readDurationField(value, "minutes", ops);
        final var months = readDurationField(value, "months", ops);
        final var nanoseconds = readDurationField(value, "nanoseconds", ops);
        final var seconds = readDurationField(value, "seconds", ops);
        final var weeks = readDurationField(value, "weeks", ops);
        final var years = readDurationField(value, "years", ops);
        if (years == null && months == null && weeks == null && days == null && hours == null && minutes == null
                && seconds == null && milliseconds == null && microseconds == null && nanoseconds == null) {
            throw new TypeErrorException("Duration-like object must contain at least one recognized property");
        }
        final var fields = new DurationFields(orZeroDuration(years), orZeroDuration(months), orZeroDuration(weeks),
                orZeroDuration(days), orZeroDuration(hours), orZeroDuration(minutes), orZeroDuration(seconds),
                orZeroDuration(milliseconds), orZeroDuration(microseconds), orZeroDuration(nanoseconds));
        DurationMath.sign(fields);
        return fields;
    }

    private static DurationFields negate(DurationFields d) {
        return DurationMath.negate(d);
    }

    private static double effectiveDays(DurationFields duration) {
        final var extraDays = DurationMath.timeUnitsNanoseconds(duration).divide(DurationMath.nanosPerUnit(Unit.DAY));
        return duration.days() + extraDays.doubleValue();
    }

    private static JsValue add(JsTemporalPlainDate receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        final var duration = toDurationFields(durationLike, ops);
        final var overflow = readOverflowOption(optionsArg, ops);
        return new JsTemporalPlainDate(IsoCalendar.addDate(receiver.fields(), duration.years(), duration.months(),
                duration.weeks(), effectiveDays(duration), overflow));
    }

    private static JsValue subtract(JsTemporalPlainDate receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        final var duration = negate(toDurationFields(durationLike, ops));
        final var overflow = readOverflowOption(optionsArg, ops);
        return new JsTemporalPlainDate(IsoCalendar.addDate(receiver.fields(), duration.years(), duration.months(),
                duration.weeks(), effectiveDays(duration), overflow));
    }

    private static JsValue until(JsTemporalPlainDate receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, false, ops);
    }

    private static JsValue since(JsTemporalPlainDate receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, true, ops);
    }

    private static JsValue difference(JsTemporalPlainDate receiver, JsValue otherArg, JsValue optionsArg,
            boolean isSince, InterpreterOps ops) {
        final var other = toPlainDate(otherArg, ops);
        if (!(optionsArg instanceof JsUndefined) && !InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        final var largestUnitRaw = readRawStringOption(optionsArg, "largestUnit", ops);
        final var incrementRaw = readRawIncrementOption(optionsArg, ops);
        final var roundingModeRaw = readRawStringOption(optionsArg, "roundingMode", ops);
        final var smallestUnitRaw = readRawStringOption(optionsArg, "smallestUnit", ops);

        final var smallestUnit = smallestUnitRaw == null ? Unit.DAY : requireDateUnit(smallestUnitRaw, "smallestUnit");
        final var largestUnitDefault = smallestUnit.isLargerThan(Unit.DAY) ? smallestUnit : Unit.DAY;
        final var largestUnit = (largestUnitRaw == null || "auto".equals(largestUnitRaw))
                ? largestUnitDefault
                : requireDateUnit(largestUnitRaw, "largestUnit");
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        final var increment = validateIncrement(incrementRaw);
        var mode = roundingModeRaw == null ? RoundingMode.TRUNC : RoundingMode.parse(roundingModeRaw);
        if (isSince) {
            mode = negateRoundingMode(mode);
        }
        var fields = IsoCalendar.differenceISODate(receiver.fields(), other.fields(), largestUnit);
        if (smallestUnit != Unit.DAY || increment != 1) {
            final var anchor = RelativeDurationMath.Anchor.plain(receiver.fields(), MIDNIGHT);
            fields = RelativeDurationMath.roundedDifference(anchor, other.fields(), MIDNIGHT, largestUnit, smallestUnit,
                    increment, mode);
        }
        if (isSince) {
            fields = negate(fields);
        }
        return new JsTemporalDuration(fields);
    }

    private static JsValue equalsMethod(JsTemporalPlainDate receiver, JsValue otherArg, InterpreterOps ops) {
        final var other = toPlainDate(otherArg, ops);
        return JsBoolean.of(receiver.sameDate(other));
    }

    private static JsValue toPlainYearMonth(JsTemporalPlainDate receiver) {
        return new JsTemporalPlainYearMonth(new Iso8601Fields(receiver.year(), receiver.month(), 1));
    }

    private static JsValue toPlainMonthDay(JsTemporalPlainDate receiver) {
        return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, receiver.month(), receiver.day()));
    }

    private static JsValue toPlainDateTime(JsTemporalPlainDate receiver, JsValue timeLike, InterpreterOps ops) {
        final var time = extractTimeFields(timeLike, ops);
        if (receiver.fields().equals(MIN_ISO_DATE) && time.equals(MIDNIGHT)) {
            throw new RangeErrorException("date value is outside the representable range for Temporal.PlainDateTime");
        }
        return new JsTemporalPlainDateTime(receiver.fields(), time);
    }

    private static IsoTimeFields extractTimeFields(JsValue timeLike, InterpreterOps ops) {
        if (timeLike == null || timeLike instanceof JsUndefined) {
            return new IsoTimeFields(0, 0, 0, 0, 0, 0);
        }
        if (timeLike instanceof JsString s) {
            return TemporalParser.parseTime(s.getValue()).time();
        }
        if (!InterpreterUtils.isObjectLike(timeLike)) {
            throw new TypeErrorException("Invalid time-like value");
        }
        final var hourRaw = ops.getMember(timeLike, new JsString("hour"));
        final var hour = timeFieldOrZero(hourRaw, "hour", 23, ops);
        final var microsecondRaw = ops.getMember(timeLike, new JsString("microsecond"));
        final var microsecond = timeFieldOrZero(microsecondRaw, "microsecond", 999, ops);
        final var millisecondRaw = ops.getMember(timeLike, new JsString("millisecond"));
        final var millisecond = timeFieldOrZero(millisecondRaw, "millisecond", 999, ops);
        final var minuteRaw = ops.getMember(timeLike, new JsString("minute"));
        final var minute = timeFieldOrZero(minuteRaw, "minute", 59, ops);
        final var nanosecondRaw = ops.getMember(timeLike, new JsString("nanosecond"));
        final var nanosecond = timeFieldOrZero(nanosecondRaw, "nanosecond", 999, ops);
        final var secondRaw = ops.getMember(timeLike, new JsString("second"));
        final var second = timeFieldOrZero(secondRaw, "second", 59, ops);
        if (hourRaw instanceof JsUndefined && microsecondRaw instanceof JsUndefined
                && millisecondRaw instanceof JsUndefined && minuteRaw instanceof JsUndefined
                && nanosecondRaw instanceof JsUndefined && secondRaw instanceof JsUndefined) {
            throw new TypeErrorException("time-like object must contain at least one recognized property");
        }
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    private static int timeFieldOrZero(JsValue value, String name, int max, InterpreterOps ops) {
        return value instanceof JsUndefined ? 0 : Math.clamp(toIntegerField(value, name, ops), 0, max);
    }

    private static JsValue toZonedDateTime(JsTemporalPlainDate receiver, JsValue options, InterpreterOps ops) {
        final var timeZoneId = extractTimeZoneId(options, ops);
        final var plainTimeArg = InterpreterUtils.isObjectLike(options)
                ? ops.getMember(options, new JsString("plainTime"))
                : JsUndefined.getInstance();
        final var time = extractTimeFields(plainTimeArg, ops);
        final var zone = ZonedDateTimeZones.zoneOf(timeZoneId);
        return ZonedDateTimeZones.resolveToZoned(receiver.fields(), time, zone, timeZoneId, Disambiguation.COMPATIBLE);
    }

    private static String extractTimeZoneId(JsValue options, InterpreterOps ops) {
        if (options instanceof JsString s) {
            return TimeZoneStringParser.parseTimeZoneIdentifierFlexible(s.getValue());
        }
        if (InterpreterUtils.isObjectLike(options)) {
            final var timeZone = ops.getMember(options, new JsString("timeZone"));
            if (timeZone instanceof JsString s) {
                return TimeZoneStringParser.parseTimeZoneIdentifierFlexible(s.getValue());
            }
        }
        throw new TypeErrorException("Temporal.PlainDate.prototype.toZonedDateTime requires a timeZone");
    }

    private static JsValue toStringMethod(JsTemporalPlainDate receiver, JsValue optionsArg, InterpreterOps ops) {
        final var calendarName = readCalendarNameOption(optionsArg, ops);
        return new JsString(TemporalFormatter.formatDate(receiver.fields(), calendarName));
    }

    private static JsValue getISOFields(JsTemporalPlainDate receiver) {
        final var obj = new JsObject();
        obj.set("calendar", new JsString("iso8601"));
        obj.set("isoDay", new JsNumber(receiver.day()));
        obj.set("isoMonth", new JsNumber(receiver.month()));
        obj.set("isoYear", new JsNumber(receiver.year()));
        return obj;
    }

}

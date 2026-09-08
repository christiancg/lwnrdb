package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTargetOrSubclassInstance;
import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.monthCode;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.monthCodeSyntaxChecked;
import static org.techhouse.simplejs.builtins.temporal.MonthCode.resolveMonthValue;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.orZeroDuration;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.readDurationField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireCalendarString;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireDateInRange;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.requireValidCalendarField;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.toIntegerField;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.negateRoundingMode;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.optionOrUndefined;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readCalendarNameOption;
import static org.techhouse.simplejs.builtins.temporal.TemporalOptions.readOverflowOption;
import static org.techhouse.simplejs.internal.temporal.TemporalAccessors.installGetter;
import static org.techhouse.simplejs.internal.temporal.TemporalBrand.isTemporalLikeObject;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MAX_ISO_YEAR;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MAX_ISO_YEAR_MONTH;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MIDNIGHT;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MIN_ISO_YEAR;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MIN_ISO_YEAR_MONTH;

import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.DurationStringParser;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.ReducedFormParser;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RelativeDurationMath;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class TemporalPlainYearMonthBuiltins {
    public static final List<String> NAMES = List.of("with", "add", "subtract", "until", "since", "equals",
            "toPlainDate", "toString", "toJSON", "toLocaleString", "getISOFields", "valueOf");

    private TemporalPlainYearMonthBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainYearMonth", (thisArg, args) -> {
            requireNewTargetOrSubclassInstance("Temporal.PlainYearMonth", thisArg);
            return withNewTargetPrototype(construct(args, ops), ops);
        });
        ctor.setLength(2);
        final var from = new JsNativeFunction("from", (_, args) -> toPlainYearMonth(arg(args, 0), arg(args, 1), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare",
                (_, args) -> new JsNumber(IsoCalendar.compareIsoDate(toPlainYearMonth(arg(args, 0), ops).fields(),
                        toPlainYearMonth(arg(args, 1), ops).fields())));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        return ctor;
    }

    public static JsValue getMethod(JsTemporalPlainYearMonth receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
            case "add" -> new JsNativeFunction("add", (_, args) -> add(receiver, arg(args, 0), arg(args, 1), ops));
            case "subtract" ->
                new JsNativeFunction("subtract", (_, args) -> subtract(receiver, arg(args, 0), arg(args, 1), ops));
            case "until" ->
                new JsNativeFunction("until", (_, args) -> until(receiver, arg(args, 0), arg(args, 1), ops));
            case "since" ->
                new JsNativeFunction("since", (_, args) -> since(receiver, arg(args, 0), arg(args, 1), ops));
            case "equals" -> new JsNativeFunction("equals", (_, args) -> equalsMethod(receiver, arg(args, 0), ops));
            case "toPlainDate" ->
                new JsNativeFunction("toPlainDate", (_, args) -> toPlainDate(receiver, arg(args, 0), ops));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "getISOFields" -> new JsNativeFunction("getISOFields", (_, _) -> getISOFields(receiver));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException(
                        "Cannot convert a Temporal.PlainYearMonth to a primitive value with valueOf; use equals() "
                                + "instead");
            });
            default -> null;
        };
    }

    public static void installAccessors(JsObject proto) {
        installGetter(proto, "year", receiver -> new JsNumber(requireReceiver(receiver, "year").year()));
        installGetter(proto, "month", receiver -> new JsNumber(requireReceiver(receiver, "month").month()));
        installGetter(proto, "monthCode",
                receiver -> new JsString(monthCode(requireReceiver(receiver, "monthCode").month())));
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
        installGetter(proto, "daysInMonth", receiver -> {
            final var ym = requireReceiver(receiver, "daysInMonth");
            return new JsNumber(IsoCalendar.daysInMonth(ym.year(), ym.month()));
        });
        installGetter(proto, "daysInYear", receiver -> {
            final var ym = requireReceiver(receiver, "daysInYear");
            return new JsNumber(IsoCalendar.daysInYear(ym.year()));
        });
        installGetter(proto, "monthsInYear", receiver -> {
            requireReceiver(receiver, "monthsInYear");
            return new JsNumber(12);
        });
        installGetter(proto, "inLeapYear", receiver -> {
            final var ym = requireReceiver(receiver, "inLeapYear");
            return JsBoolean.of(IsoCalendar.isLeapYear(ym.year()));
        });
    }

    private static JsTemporalPlainYearMonth requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalPlainYearMonth ym) {
            return ym;
        }
        if (receiver instanceof JsObject wrapper
                && wrapper.getPrimitive() instanceof JsTemporalPlainYearMonth wrapped) {
            return wrapped;
        }
        throw new TypeErrorException(
                "Temporal.PlainYearMonth.prototype." + method + " called on an incompatible receiver");
    }

    private static JsTemporalPlainYearMonth construct(List<JsValue> args, InterpreterOps ops) {
        final var year = toIntegerField(arg(args, 0), "year", ops);
        final var month = toIntegerField(arg(args, 1), "month", ops);
        final var calendarArg = arg(args, 2);
        if (!(calendarArg instanceof JsUndefined)) {
            requireCalendarString(calendarArg);
        }
        final var referenceISODayArg = arg(args, 3);
        final var referenceISODay = referenceISODayArg instanceof JsUndefined
                ? 1
                : toIntegerField(referenceISODayArg, "referenceISODay", ops);
        final var result = IsoCalendar.regulateCalendarDate(year, month, referenceISODay, RegulateOverflow.REJECT);
        requireYearMonthInRange(result.year(), result.month());
        return new JsTemporalPlainYearMonth(result);
    }

    private static void requireYearMonthInRange(int year, int month) {
        if (year < MIN_ISO_YEAR || year > MAX_ISO_YEAR || (year == MIN_ISO_YEAR && month < MIN_ISO_YEAR_MONTH)
                || (year == MAX_ISO_YEAR && month > MAX_ISO_YEAR_MONTH)) {
            throw new RangeErrorException(
                    "year-month " + year + "-" + month + " is outside the representable range of PlainYearMonth");
        }
    }

    private static JsTemporalPlainYearMonth toPlainYearMonth(JsValue item, InterpreterOps ops) {
        return toPlainYearMonth(item, JsUndefined.getInstance(), ops);
    }

    private static JsTemporalPlainYearMonth toPlainYearMonth(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        if (item instanceof JsTemporalPlainYearMonth ym) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainYearMonth(ym.fields());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainYearMonth wrapped) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainYearMonth(wrapped.fields());
        }
        if (item instanceof JsTemporalPlainDate pd) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainYearMonth(new Iso8601Fields(pd.year(), pd.month(), 1));
        }
        if (item instanceof JsTemporalPlainDateTime dt) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainYearMonth(new Iso8601Fields(dt.year(), dt.month(), 1));
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            readOverflowOption(optionsArg, ops);
            final var date = zdt.isoFieldsAtLocal().date();
            return new JsTemporalPlainYearMonth(new Iso8601Fields(date.year(), date.month(), 1));
        }
        if (item instanceof JsString s) {
            final var parsed = ReducedFormParser.parseYearMonth(s.getValue());
            if (parsed.calendar() != null) {
                TemporalCalendarIdentifier.requireBuiltinCalendar(parsed.calendar());
            }
            final var date = parsed.date();
            requireYearMonthInRange(date.year(), date.month());
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainYearMonth(new Iso8601Fields(date.year(), date.month(), 1));
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return yearMonthFromFields(item, optionsArg, ops);
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.PlainYearMonth");
    }

    private static JsTemporalPlainYearMonth yearMonthFromFields(JsValue obj, JsValue optionsArg, InterpreterOps ops) {
        requireValidCalendarField(obj, ops);
        final var monthValue = ops.getMember(obj, new JsString("month"));
        final var month = monthValue instanceof JsUndefined
                ? null
                : (Integer) requirePositiveMonthField(monthValue, ops);
        final var monthCode = monthCodeSyntaxChecked(ops.getMember(obj, new JsString("monthCode")), ops);
        final var yearValue = ops.getMember(obj, new JsString("year"));
        if (yearValue instanceof JsUndefined) {
            throw new TypeErrorException("year is required");
        }
        final var year = toIntegerField(yearValue, "year", ops);
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = resolveMonthValue(month, monthCode);
        final var result = IsoCalendar.regulateCalendarDate(year, resolvedMonth, 1, overflow);
        requireYearMonthInRange(result.year(), result.month());
        return new JsTemporalPlainYearMonth(result);
    }

    private static int requiredDayField(JsValue obj, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString("day"));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException("day is required");
        }
        return toIntegerField(value, "day", ops);
    }

    private static int requirePositiveMonthField(JsValue value, InterpreterOps ops) {
        final var truncated = toIntegerField(value, "month", ops);
        if (truncated < 1) {
            throw new RangeErrorException("month must be a positive integer, got " + truncated);
        }
        return truncated;
    }

    private static String readUnitOptionRaw(JsValue optionsArg, String key, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, key, ops);
        return value instanceof JsUndefined ? null : JsCoercion.toStr(value, ops);
    }

    private static Unit resolveLargestUnit(String raw) {
        if (raw == null || "auto".equals(raw)) {
            return Unit.YEAR;
        }
        return requireYearOrMonthUnit(raw, "largestUnit");
    }

    private static Unit resolveSmallestUnit(String raw) {
        return raw == null ? Unit.MONTH : requireYearOrMonthUnit(raw, "smallestUnit");
    }

    private static Unit requireYearOrMonthUnit(String raw, String optionName) {
        final var unit = Unit.parseTemporalUnit(raw);
        if (unit != Unit.YEAR && unit != Unit.MONTH) {
            throw new RangeErrorException(
                    optionName + " must be \"year\" or \"month\" for Temporal.PlainYearMonth, got: " + raw);
        }
        return unit;
    }

    private static long readIncrementOptionRaw(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "roundingIncrement", ops);
        return value instanceof JsUndefined ? 1 : toIntegerField(value, "roundingIncrement", ops);
    }

    private static void requireValidIncrement(long number) {
        if (number < 1 || number > 1_000_000_000) {
            throw new RangeErrorException("roundingIncrement out of range: " + number);
        }
    }

    private static String readRoundingModeOptionRaw(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "roundingMode", ops);
        return value instanceof JsUndefined ? null : JsCoercion.toStr(value, ops);
    }

    private static RoundingMode resolveRoundingMode(String raw) {
        return raw == null ? RoundingMode.TRUNC : RoundingMode.parse(raw);
    }

    private static JsValue with(JsTemporalPlainYearMonth receiver, JsValue fieldsLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(fieldsLike) || isTemporalLikeObject(fieldsLike)) {
            throw new TypeErrorException("Temporal.PlainYearMonth.prototype.with argument must be an object");
        }
        final var calendarValue = ops.getMember(fieldsLike, new JsString("calendar"));
        if (!(calendarValue instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a calendar property");
        }
        final var timeZoneValue = ops.getMember(fieldsLike, new JsString("timeZone"));
        if (!(timeZoneValue instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a timeZone property");
        }
        final var monthValue = ops.getMember(fieldsLike, new JsString("month"));
        final var month = monthValue instanceof JsUndefined
                ? null
                : (Integer) requirePositiveMonthField(monthValue, ops);
        final var monthCode = monthCodeSyntaxChecked(ops.getMember(fieldsLike, new JsString("monthCode")), ops);
        final var yearValue = ops.getMember(fieldsLike, new JsString("year"));
        final var year = yearValue instanceof JsUndefined ? null : (Integer) toIntegerField(yearValue, "year", ops);
        if (month == null && monthCode == null && year == null) {
            throw new TypeErrorException("with() argument must contain at least one of year, month, monthCode");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = month == null && monthCode == null
                ? receiver.month()
                : resolveMonthValue(month, monthCode);
        final var resolvedYear = year != null ? year : receiver.year();
        final var result = IsoCalendar.regulateCalendarDate(resolvedYear, resolvedMonth, 1, overflow);
        requireYearMonthInRange(result.year(), result.month());
        return new JsTemporalPlainYearMonth(result);
    }

    private static Iso8601Fields calendarDate(JsTemporalPlainYearMonth yearMonth) {
        return new Iso8601Fields(yearMonth.year(), yearMonth.month(), 1);
    }

    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalDuration wrapped) {
            return wrapped.getFields();
        }
        if (value instanceof JsString s) {
            return DurationStringParser.parseDuration(s.getValue());
        }
        if (!InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("Invalid Temporal.Duration-like value");
        }
        return durationLikeFields(value, ops);
    }

    private static DurationFields durationLikeFields(JsValue value, InterpreterOps ops) {
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

    private static void requireDateOnlyDuration(DurationFields d) {
        if (d.weeks() != 0 || d.days() != 0 || d.hours() != 0 || d.minutes() != 0 || d.seconds() != 0
                || d.milliseconds() != 0 || d.microseconds() != 0 || d.nanoseconds() != 0) {
            throw new RangeErrorException(
                    "Temporal.PlainYearMonth.prototype.add/subtract only accepts year/month duration components");
        }
    }

    private static JsValue add(JsTemporalPlainYearMonth receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        final var duration = toDurationFields(durationLike, ops);
        final var overflow = readOverflowOption(optionsArg, ops);
        requireDateOnlyDuration(duration);
        final var receiverDate = calendarDate(receiver);
        requireDateInRange(receiverDate);
        final var added = IsoCalendar.addDate(receiverDate, duration.years(), duration.months(), duration.weeks(),
                duration.days(), overflow);
        final var result = new Iso8601Fields(added.year(), added.month(), 1);
        requireDateInRange(result);
        return new JsTemporalPlainYearMonth(result);
    }

    private static JsValue subtract(JsTemporalPlainYearMonth receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        final var duration = negate(toDurationFields(durationLike, ops));
        final var overflow = readOverflowOption(optionsArg, ops);
        requireDateOnlyDuration(duration);
        final var receiverDate = calendarDate(receiver);
        requireDateInRange(receiverDate);
        final var added = IsoCalendar.addDate(receiverDate, duration.years(), duration.months(), duration.weeks(),
                duration.days(), overflow);
        final var result = new Iso8601Fields(added.year(), added.month(), 1);
        requireDateInRange(result);
        return new JsTemporalPlainYearMonth(result);
    }

    private static JsValue until(JsTemporalPlainYearMonth receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, false, ops);
    }

    private static JsValue since(JsTemporalPlainYearMonth receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, true, ops);
    }

    private static JsValue difference(JsTemporalPlainYearMonth receiver, JsValue otherArg, JsValue optionsArg,
            boolean isSince, InterpreterOps ops) {
        final var other = toPlainYearMonth(otherArg, ops);
        final var largestUnitRaw = readUnitOptionRaw(optionsArg, "largestUnit", ops);
        final var increment = readIncrementOptionRaw(optionsArg, ops);
        final var roundingModeRaw = readRoundingModeOptionRaw(optionsArg, ops);
        final var smallestUnitRaw = readUnitOptionRaw(optionsArg, "smallestUnit", ops);
        final var largestUnit = resolveLargestUnit(largestUnitRaw);
        requireValidIncrement(increment);
        var mode = resolveRoundingMode(roundingModeRaw);
        final var smallestUnit = resolveSmallestUnit(smallestUnitRaw);
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        if (isSince) {
            mode = negateRoundingMode(mode);
        }
        if (receiver.year() == other.year() && receiver.month() == other.month()) {
            return new JsTemporalDuration(DurationFields.ZERO);
        }
        final var receiverDate = calendarDate(receiver);
        final var otherDate = calendarDate(other);
        requireDateInRange(receiverDate);
        requireDateInRange(otherDate);
        var fields = IsoCalendar.differenceISODate(receiverDate, otherDate, largestUnit);
        if (smallestUnit != Unit.MONTH || increment != 1) {
            final var anchor = RelativeDurationMath.Anchor.plain(receiverDate, MIDNIGHT);
            fields = RelativeDurationMath.roundedDifference(anchor, otherDate, MIDNIGHT, largestUnit, smallestUnit,
                    increment, mode);
        }
        if (isSince) {
            fields = negate(fields);
        }
        return new JsTemporalDuration(fields);
    }

    private static JsValue equalsMethod(JsTemporalPlainYearMonth receiver, JsValue otherArg, InterpreterOps ops) {
        final var other = toPlainYearMonth(otherArg, ops);
        return JsBoolean.of(IsoCalendar.compareIsoDate(receiver.fields(), other.fields()) == 0);
    }

    private static JsValue toPlainDate(JsTemporalPlainYearMonth receiver, JsValue item, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(item)) {
            throw new TypeErrorException(
                    "Temporal.PlainYearMonth.prototype.toPlainDate requires an object with a day property");
        }
        final var day = requiredDayField(item, ops);
        final var result = IsoCalendar.regulateDate(receiver.year(), receiver.month(), day, RegulateOverflow.CONSTRAIN);
        requireDateInRange(result);
        return new JsTemporalPlainDate(result);
    }

    private static JsValue toStringMethod(JsTemporalPlainYearMonth receiver, JsValue optionsArg, InterpreterOps ops) {
        final var calendarName = readCalendarNameOption(optionsArg, ops);
        return new JsString(TemporalFormatter.formatYearMonth(receiver.fields(), calendarName));
    }

    private static JsValue getISOFields(JsTemporalPlainYearMonth receiver) {
        final var obj = new JsObject();
        obj.set("calendar", new JsString("iso8601"));
        obj.set("isoDay", new JsNumber(receiver.referenceISODay()));
        obj.set("isoMonth", new JsNumber(receiver.month()));
        obj.set("isoYear", new JsNumber(receiver.year()));
        return obj;
    }

}

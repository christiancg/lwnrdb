package org.techhouse.simplejs.builtins;

import java.util.List;
import java.util.function.Function;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RelativeDurationMath;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * {@code Temporal.PlainYearMonth}: a thin, "iso8601"-calendar-only projection of {@code PlainDate}
 * onto year+month (see the feature plan's scope-defining finding). Calendar arithmetic
 * (add/subtract/until/since) is computed against a canonical reference date of day 1 - not the
 * receiver's stored {@code referenceISODay} - mirroring {@code CalendarYearMonthFromFields}'s own
 * day-1 normalization, so a value constructed with a non-default reference day still arithmetics
 * exactly like one built with the default. {@code Temporal.Duration} is a real, merged type by this
 * phase, so {@code add}/{@code subtract} accept it directly (alongside a duration-like string/object,
 * same as {@code Temporal.PlainDate}) and {@code until}/{@code since} return a real instance instead
 * of a duck-typed object.
 */
public final class TemporalPlainYearMonthBuiltins {
    public static final List<String> NAMES = List.of("with", "add", "subtract", "until", "since", "equals",
            "toPlainDate", "toString", "toJSON", "toLocaleString", "getISOFields", "valueOf");

    private TemporalPlainYearMonthBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainYearMonth", (_, args) -> {
            requireNewTarget();
            return construct(args, ops);
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

    private static void installGetter(JsObject proto, String name, Function<JsValue, JsValue> impl) {
        final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> impl.apply(thisArg));
        getter.setLength(0);
        proto.defineAccessor(name, getter, null);
        proto.setFlags(name, new JsObject.PropertyFlags(true, false, true));
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

    private static void requireNewTarget() {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (newTarget == null || newTarget instanceof JsUndefined) {
            throw new TypeErrorException("Constructor Temporal.PlainYearMonth requires 'new'");
        }
    }

    private static JsValue construct(List<JsValue> args, InterpreterOps ops) {
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
        return new JsTemporalPlainYearMonth(
                IsoCalendar.regulateDate(year, month, referenceISODay, RegulateOverflow.REJECT));
    }

    // Constructor argument: a bare calendar identifier only; a non-string value is a TypeError.
    private static String requireCalendarString(JsValue calendarArg) {
        if (!(calendarArg instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        return TemporalCalendarIdentifier.canonicalizeBare(s.getValue());
    }

    private static JsTemporalPlainYearMonth toPlainYearMonth(JsValue item, InterpreterOps ops) {
        return toPlainYearMonth(item, JsUndefined.getInstance(), ops);
    }

    // ToTemporalYearMonth: accepts an existing PlainYearMonth (referenceISODay preserved), an ISO
    // year-month or full-date string (referenceISODay taken from the parse), or a year-month-like
    // object (year + month/monthCode, referenceISODay forced to 1, regulated per the `overflow`
    // option). The overflow option is validated even along the copy/string branches, matching
    // GetTemporalOverflowOption's unconditional call in the spec algorithm.
    private static JsTemporalPlainYearMonth toPlainYearMonth(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        final var overflow = readOverflowOption(optionsArg, ops);
        if (item instanceof JsTemporalPlainYearMonth ym) {
            return new JsTemporalPlainYearMonth(ym.fields());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainYearMonth wrapped) {
            return new JsTemporalPlainYearMonth(wrapped.fields());
        }
        // ToTemporalYearMonth reads a Temporal object argument's year/month fields the same way it
        // reads a plain object's (PrepareTemporalFields calls Get, not an internal-slot bypass), but
        // these Temporal types are not JsObject, so they need their own branches here to reach the
        // same year+month result (referenceISODay forced to 1, per CalendarYearMonthFromFields).
        if (item instanceof JsTemporalPlainDate pd) {
            return new JsTemporalPlainYearMonth(new Iso8601Fields(pd.year(), pd.month(), 1));
        }
        if (item instanceof JsTemporalPlainDateTime dt) {
            return new JsTemporalPlainYearMonth(new Iso8601Fields(dt.year(), dt.month(), 1));
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            final var date = zdt.isoFieldsAtLocal().date();
            return new JsTemporalPlainYearMonth(new Iso8601Fields(date.year(), date.month(), 1));
        }
        if (item instanceof JsString s) {
            final var parsed = TemporalParser.parseYearMonth(s.getValue());
            if (parsed.calendar() != null) {
                TemporalCalendarIdentifier.canonicalizeBare(parsed.calendar());
            }
            // Per CalendarYearMonthFromFields, the iso8601 calendar always normalises referenceISODay
            // to 1 - even when the source string was a full date/date-time carrying a real day-of-month
            // (e.g. "2019-12-15T00+00"), not just the reduced "YYYY-MM" form.
            final var date = parsed.date();
            return new JsTemporalPlainYearMonth(new Iso8601Fields(date.year(), date.month(), 1));
        }
        if (item instanceof JsObject obj) {
            return new JsTemporalPlainYearMonth(yearMonthFromFields(obj, overflow, ops));
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.PlainYearMonth");
    }

    private static Iso8601Fields yearMonthFromFields(JsObject obj, RegulateOverflow overflow, InterpreterOps ops) {
        requireValidCalendarField(obj, ops);
        final var year = requiredIntegerField(obj, "year", ops);
        final var month = resolveMonth(obj, ops);
        return IsoCalendar.regulateDate(year, month, 1, overflow);
    }

    // Property-bag `calendar` field: accepts a bare identifier or a full ISO string carrying (or
    // defaulting) a u-ca annotation. A `calendar` that is itself a Temporal object is taken via its
    // fast path (its calendar is implicitly "iso8601" in this ISO-only engine, so its
    // `calendar`/`calendarId` getters are never read, matching ToTemporalCalendar's own
    // internal-slot fast path).
    private static void requireValidCalendarField(JsObject obj, InterpreterOps ops) {
        final var calendarValue = ops.getMember(obj, new JsString("calendar"));
        if (calendarValue instanceof JsUndefined || calendarValue instanceof JsTemporalPlainDate
                || calendarValue instanceof JsTemporalPlainDateTime || calendarValue instanceof JsTemporalPlainMonthDay
                || calendarValue instanceof JsTemporalPlainYearMonth
                || calendarValue instanceof JsTemporalZonedDateTime) {
            return;
        }
        if (!(calendarValue instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        TemporalCalendarIdentifier.canonicalizeFlexible(s.getValue());
    }

    private static int requiredIntegerField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required");
        }
        return toIntegerField(value, name, ops);
    }

    private static int resolveMonth(JsObject obj, InterpreterOps ops) {
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        final var monthValue = ops.getMember(obj, new JsString("month"));
        if (!(monthCodeValue instanceof JsUndefined)) {
            final var resolved = parseMonthCode(JsCoercion.toStr(monthCodeValue, ops));
            if (!(monthValue instanceof JsUndefined) && toIntegerField(monthValue, "month", ops) != resolved) {
                throw new RangeErrorException("month and monthCode are inconsistent");
            }
            return resolved;
        }
        if (monthValue instanceof JsUndefined) {
            throw new TypeErrorException("month or monthCode is required");
        }
        return toIntegerField(monthValue, "month", ops);
    }

    private static int parseMonthCode(String code) {
        if (code.length() == 3 && code.charAt(0) == 'M') {
            try {
                final var value = Integer.parseInt(code.substring(1));
                if (value >= 1 && value <= 12) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Falls through to the RangeError below.
            }
        }
        throw new RangeErrorException("Invalid monthCode: " + code);
    }

    private static int toIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException(name + " must be a finite integer, got " + number);
        }
        return (int) number;
    }

    private static String monthCode(int month) {
        return "M" + pad2(month);
    }

    private static String pad2(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static RegulateOverflow readOverflowOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "overflow", ops);
        return value instanceof JsUndefined
                ? RegulateOverflow.CONSTRAIN
                : RegulateOverflow.parse(JsCoercion.toStr(value, ops));
    }

    private static Unit readLargestUnitOption(JsValue optionsArg, Unit fallback, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "largestUnit", ops);
        if (value instanceof JsUndefined) {
            return fallback;
        }
        final var raw = JsCoercion.toStr(value, ops);
        return "auto".equals(raw) ? fallback : requireYearOrMonthUnit(raw, "largestUnit");
    }

    private static Unit readSmallestUnitOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "smallestUnit", ops);
        return value instanceof JsUndefined
                ? Unit.MONTH
                : requireYearOrMonthUnit(JsCoercion.toStr(value, ops), "smallestUnit");
    }

    private static Unit requireYearOrMonthUnit(String raw, String optionName) {
        final var unit = Unit.parseTemporalUnit(raw);
        if (unit != Unit.YEAR && unit != Unit.MONTH) {
            throw new RangeErrorException(
                    optionName + " must be \"year\" or \"month\" for Temporal.PlainYearMonth, got: " + raw);
        }
        return unit;
    }

    private static long readIncrementOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "roundingIncrement", ops);
        if (value instanceof JsUndefined) {
            return 1;
        }
        final var number = toIntegerField(value, "roundingIncrement", ops);
        if (number < 1 || number > 1_000_000_000) {
            throw new RangeErrorException("roundingIncrement out of range: " + number);
        }
        return number;
    }

    private static RoundingMode readRoundingModeOption(JsValue optionsArg, InterpreterOps ops, RoundingMode fallback) {
        final var value = optionOrUndefined(optionsArg, "roundingMode", ops);
        return value instanceof JsUndefined ? fallback : RoundingMode.parse(JsCoercion.toStr(value, ops));
    }

    private static RoundingMode negateRoundingMode(RoundingMode mode) {
        return switch (mode) {
            case CEIL -> RoundingMode.FLOOR;
            case FLOOR -> RoundingMode.CEIL;
            case HALF_CEIL -> RoundingMode.HALF_FLOOR;
            case HALF_FLOOR -> RoundingMode.HALF_CEIL;
            default -> mode;
        };
    }

    private static TemporalFormatter.CalendarName readCalendarNameOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "calendarName", ops);
        return value instanceof JsUndefined
                ? TemporalFormatter.CalendarName.AUTO
                : TemporalFormatter.CalendarName.parse(JsCoercion.toStr(value, ops));
    }

    private static JsValue optionOrUndefined(JsValue optionsArg, String key, InterpreterOps ops) {
        if (optionsArg == null || optionsArg instanceof JsUndefined) {
            return JsUndefined.getInstance();
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        return ops.getMember(optionsArg, new JsString(key));
    }

    private static JsValue with(JsTemporalPlainYearMonth receiver, JsValue fieldsLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(fieldsLike)) {
            throw new TypeErrorException("Temporal.PlainYearMonth.prototype.with argument must be an object");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var year = fieldOrDefault(fieldsLike, "year", receiver.year(), ops);
        final var month = resolveMonthWith(fieldsLike, receiver.month(), ops);
        return new JsTemporalPlainYearMonth(IsoCalendar.regulateDate(year, month, 1, overflow));
    }

    private static int fieldOrDefault(JsValue obj, String name, int defaultValue, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        return value instanceof JsUndefined ? defaultValue : toIntegerField(value, name, ops);
    }

    private static int resolveMonthWith(JsValue obj, int defaultMonth, InterpreterOps ops) {
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        if (!(monthCodeValue instanceof JsUndefined)) {
            return parseMonthCode(JsCoercion.toStr(monthCodeValue, ops));
        }
        return fieldOrDefault(obj, "month", defaultMonth, ops);
    }

    // The reference date every calendar computation below is anchored to: day 1, regardless of the
    // receiver's own (possibly non-default) referenceISODay - see the class-level note.
    private static Iso8601Fields calendarDate(JsTemporalPlainYearMonth yearMonth) {
        return new Iso8601Fields(yearMonth.year(), yearMonth.month(), 1);
    }

    // ToTemporalDuration: a real Temporal.Duration is used directly, alongside the same ISO duration
    // string / duration-like object forms Temporal.PlainDate's own duration argument accepts.
    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalDuration wrapped) {
            return wrapped.getFields();
        }
        if (value instanceof JsString s) {
            return TemporalParser.parseDuration(s.getValue());
        }
        if (!InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("Invalid Temporal.Duration-like value");
        }
        return durationLikeFields(value, ops);
    }

    private static DurationFields durationLikeFields(JsValue value, InterpreterOps ops) {
        final var years = readDurationField(value, "years", ops);
        final var months = readDurationField(value, "months", ops);
        final var weeks = readDurationField(value, "weeks", ops);
        final var days = readDurationField(value, "days", ops);
        final var hours = readDurationField(value, "hours", ops);
        final var minutes = readDurationField(value, "minutes", ops);
        final var seconds = readDurationField(value, "seconds", ops);
        final var milliseconds = readDurationField(value, "milliseconds", ops);
        final var microseconds = readDurationField(value, "microseconds", ops);
        final var nanoseconds = readDurationField(value, "nanoseconds", ops);
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

    private static Double readDurationField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            return null;
        }
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.floor(number)) {
            throw new RangeErrorException(name + " must be an integer");
        }
        return number;
    }

    private static double orZeroDuration(Double value) {
        return value == null ? 0.0 : value;
    }

    private static DurationFields negate(DurationFields d) {
        return DurationMath.negate(d);
    }

    private static JsValue add(JsTemporalPlainYearMonth receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        final var duration = toDurationFields(durationLike, ops);
        final var overflow = readOverflowOption(optionsArg, ops);
        final var added = IsoCalendar.addDate(calendarDate(receiver), duration.years(), duration.months(),
                duration.weeks(), duration.days(), overflow);
        return new JsTemporalPlainYearMonth(new Iso8601Fields(added.year(), added.month(), 1));
    }

    private static JsValue subtract(JsTemporalPlainYearMonth receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        final var duration = negate(toDurationFields(durationLike, ops));
        final var overflow = readOverflowOption(optionsArg, ops);
        final var added = IsoCalendar.addDate(calendarDate(receiver), duration.years(), duration.months(),
                duration.weeks(), duration.days(), overflow);
        return new JsTemporalPlainYearMonth(new Iso8601Fields(added.year(), added.month(), 1));
    }

    private static JsValue until(JsTemporalPlainYearMonth receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, false, ops);
    }

    private static JsValue since(JsTemporalPlainYearMonth receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, true, ops);
    }

    private static final IsoTimeFields MIDNIGHT = new IsoTimeFields(0, 0, 0, 0, 0, 0);

    private static JsValue difference(JsTemporalPlainYearMonth receiver, JsValue otherArg, JsValue optionsArg,
            boolean isSince, InterpreterOps ops) {
        final var other = toPlainYearMonth(otherArg, ops);
        final var smallestUnit = readSmallestUnitOption(optionsArg, ops);
        final var largestUnit = readLargestUnitOption(optionsArg, Unit.YEAR, ops);
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        final var increment = readIncrementOption(optionsArg, ops);
        var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        if (isSince) {
            mode = negateRoundingMode(mode);
        }
        final var receiverDate = calendarDate(receiver);
        final var otherDate = calendarDate(other);
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

    // equals compares the receiver's actual [[ISODate]] slot (including referenceISODay), not the
    // day-1-canonicalized form add/until/since compute against - two PlainYearMonths built with
    // different explicit referenceISODay values are therefore not equal, mirroring CompareISODate.
    private static JsValue equalsMethod(JsTemporalPlainYearMonth receiver, JsValue otherArg, InterpreterOps ops) {
        final var other = toPlainYearMonth(otherArg, ops);
        return JsBoolean.of(IsoCalendar.compareIsoDate(receiver.fields(), other.fields()) == 0);
    }

    private static JsValue toPlainDate(JsTemporalPlainYearMonth receiver, JsValue item, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(item)) {
            throw new TypeErrorException(
                    "Temporal.PlainYearMonth.prototype.toPlainDate requires an object with a day property");
        }
        final var day = requiredIntegerField(item, "day", ops);
        return new JsTemporalPlainDate(
                IsoCalendar.regulateDate(receiver.year(), receiver.month(), day, RegulateOverflow.CONSTRAIN));
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

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}

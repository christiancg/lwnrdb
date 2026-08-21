package org.techhouse.simplejs.builtins;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
 * {@code Temporal.Duration}: ten signed fields with no calendar dependency by itself. Balancing/
 * rounding/totaling/comparing across years, months or weeks is genuinely calendar-dependent (a
 * "month" has no fixed length) and needs a real anchor date to resolve - the {@code relativeTo}
 * option, read via {@link #toRelativeToAnchor} and applied through {@link RelativeDurationMath}
 * (shared with {@code PlainDateTime}/{@code ZonedDateTime}'s {@code since}/{@code until}, whose
 * relativeTo is implicitly the receiver). When no such calendar unit is involved, {@link
 * DurationMath}'s calendar-independent (day-and-below) math still handles everything directly, with
 * no anchor required.
 */
public final class TemporalDurationBuiltins {
    private static final String RELATIVE_TO_REQUIRED = "relativeTo is required to round/total/compare a "
            + "Temporal.Duration with years, months or weeks, or a unit larger than days";

    private static final List<String> FIELD_ORDER = List.of("years", "months", "weeks", "days", "hours", "minutes",
            "seconds", "milliseconds", "microseconds", "nanoseconds");

    // A duration-like property bag (with()/durationLikeFields()) is read alphabetically, not in the
    // constructor's years-first positional order - PrepareTemporalFields sorts the recognized keys.
    private static final List<String> PROPERTY_READ_ORDER = List.of("days", "hours", "microseconds", "milliseconds",
            "minutes", "months", "nanoseconds", "seconds", "weeks", "years");

    private static final IsoTimeFields MIDNIGHT = new IsoTimeFields(0, 0, 0, 0, 0, 0);

    public static final List<String> METHOD_NAMES = List.of("with", "negated", "abs", "add", "subtract", "round",
            "total", "toString", "toJSON", "toLocaleString", "valueOf");

    // Per-instance field access is an accessor property (a getter), not a method - installed on the
    // prototype via JsObject.defineAccessor/PropertyFlags, mirroring how e.g. regex flag accessors
    // are installed, rather than through the generic method-dispatch wrapper every other name here
    // goes through.
    public static final List<String> ACCESSOR_NAMES = List.of("years", "months", "weeks", "days", "hours", "minutes",
            "seconds", "milliseconds", "microseconds", "nanoseconds", "sign", "blank");

    public static final List<String> NAMES = concat(METHOD_NAMES, ACCESSOR_NAMES);

    private TemporalDurationBuiltins() {
    }

    private static List<String> concat(List<String> a, List<String> b) {
        final var combined = new ArrayList<String>(a.size() + b.size());
        combined.addAll(a);
        combined.addAll(b);
        return List.copyOf(combined);
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("Duration", (thisArg, args) -> {
            requireNewTarget(thisArg);
            return construct(args, ops);
        });
        final var from = new JsNativeFunction("from", (_, args) -> from(arg(args, 0), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare", (_, args) -> compare(args, ops));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        return ctor;
    }

    // Unlike Map/Date/DisposableStack (always reached as a bare global identifier, so a plain call's
    // thisArg is reliably undefined), Temporal.Duration only ever exists as a member of the Temporal
    // namespace object - so a plain `Temporal.Duration()` call's thisArg is that namespace object, not
    // undefined, and the MapBuiltins-style "thisArg is not undefined" check alone would wrongly accept
    // it. A genuine subclass super() call is told apart instead by instance provenance: ClassEvaluator
    // stamps the under-construction instance's klass before running any super constructor (see
    // JsClass.construct), which a plain object such as the Temporal namespace never carries.
    private static void requireNewTarget(JsValue thisArg) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (newTarget != null && !(newTarget instanceof JsUndefined)) {
            return;
        }
        if (thisArg instanceof JsObject object && object.getKlass() != null) {
            return;
        }
        throw new TypeErrorException("Constructor Temporal.Duration requires 'new'");
    }

    private static JsValue construct(List<JsValue> args, InterpreterOps ops) {
        final var values = new double[FIELD_ORDER.size()];
        for (var i = 0; i < values.length; i++) {
            values[i] = integerField(args, i, ops);
        }
        final var fields = toFields(values);
        DurationMath.sign(fields);
        return new JsTemporalDuration(fields);
    }

    private static DurationFields toFields(double[] v) {
        return new DurationFields(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
    }

    private static double integerField(List<JsValue> args, int index, InterpreterOps ops) {
        if (index >= args.size()) {
            return 0;
        }
        final var value = args.get(index);
        if (value instanceof JsUndefined) {
            return 0;
        }
        return integerValue(value, ops);
    }

    private static double integerValue(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (!Double.isFinite(number)) {
            throw new RangeErrorException("Duration field must be a finite integer, got " + number);
        }
        if (number != Math.floor(number)) {
            throw new RangeErrorException("Duration field must be an integer, got " + number);
        }
        // ToIntegerIfIntegral normalises -0 to +0 (a Duration field is never observably negative
        // zero, even though the sign otherwise matters for the overall duration).
        return number == 0.0 ? 0.0 : number;
    }

    private static JsValue from(JsValue item, InterpreterOps ops) {
        if (item instanceof JsTemporalDuration duration) {
            return new JsTemporalDuration(duration.getFields());
        }
        if (item instanceof JsString str) {
            final var fields = TemporalParser.parseDuration(str.getValue());
            DurationMath.sign(fields);
            return new JsTemporalDuration(fields);
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return new JsTemporalDuration(durationLikeFields(item, ops));
        }
        throw new TypeErrorException(
                "Temporal.Duration.from requires a Temporal.Duration, an ISO 8601 duration string, "
                        + "or a duration-like object");
    }

    private static DurationFields durationLikeFields(JsValue item, InterpreterOps ops) {
        final var values = new double[FIELD_ORDER.size()];
        var any = false;
        for (final var name : PROPERTY_READ_ORDER) {
            final var member = ops.getMember(item, new JsString(name));
            if (member == null || member instanceof JsUndefined) {
                continue;
            }
            any = true;
            values[FIELD_ORDER.indexOf(name)] = integerValue(member, ops);
        }
        if (!any) {
            throw new TypeErrorException(
                    "Invalid duration-like object: at least one of years/months/weeks/days/hours/minutes/seconds/"
                            + "milliseconds/microseconds/nanoseconds must be present");
        }
        final var fields = toFields(values);
        DurationMath.sign(fields);
        return fields;
    }

    // Two durations with literally identical fields are equal without needing a relativeTo, even if
    // both carry a year/month/week component - no calendar math is required to know a duration equals
    // itself. Only a genuine difference in a calendar-dependent field requires relativeTo.
    private static JsValue compare(List<JsValue> args, InterpreterOps ops) {
        final var one = toDurationFields(arg(args, 0), ops);
        final var two = toDurationFields(arg(args, 1), ops);
        // GetOptionsObject validates the options argument's type before the equal-durations shortcut
        // below, so a bad options value (e.g. null) still throws even when the two durations compare
        // equal without ever needing to look at relativeTo.
        final var optionsArg = arg(args, 2);
        final var relativeToValue = optionOrUndefined(optionsArg, "relativeTo", ops);
        // relativeTo is validated (format, range, etc.) even when the equal-durations shortcut below
        // would make the actual comparison trivial - ToRelativeTemporalObject runs unconditionally.
        final var anchor = relativeToValue instanceof JsUndefined ? null : toRelativeToAnchor(relativeToValue, ops);
        if (one.equals(two)) {
            return new JsNumber(0);
        }
        if (anchor != null) {
            return new JsNumber(RelativeDurationMath.compareApplied(anchor, one, two, RegulateOverflow.CONSTRAIN));
        }
        if (hasCalendarUnits(one) || hasCalendarUnits(two)) {
            throw new RangeErrorException(RELATIVE_TO_REQUIRED);
        }
        final var totalOne = DurationMath.totalNanoseconds(one);
        final var totalTwo = DurationMath.totalNanoseconds(two);
        return new JsNumber(totalOne.compareTo(totalTwo));
    }

    private static boolean hasCalendarUnits(DurationFields f) {
        return f.years() != 0 || f.months() != 0 || f.weeks() != 0;
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

    // ---- relativeTo: resolves a real Temporal.PlainDate/PlainDateTime/ZonedDateTime, an ISO string,
    // or a fields-like object into a RelativeDurationMath.Anchor. Mirrors ToRelativeTemporalObject.

    private static RelativeDurationMath.Anchor toRelativeToAnchor(JsValue value, InterpreterOps ops) {
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

    // A relativeTo string is a plain date/date-time UNLESS it carries a bracketed IANA/offset time
    // zone annotation (a numeric UTC offset alone, with no bracket, does not make it zoned) - matches
    // Temporal.PlainDateTime.from's own "any offset without a bracket is ignored" convention.
    private static RelativeDurationMath.Anchor parseRelativeToString(String text) {
        final var parsed = TemporalParser.parseRelativeToString(text);
        if (parsed.calendar() != null) {
            TemporalCalendarIdentifier.canonicalizeBare(parsed.calendar());
        }
        final var date = parsed.date();
        final var time = parsed.time() != null ? parsed.time() : MIDNIGHT;
        if (parsed.timeZoneId() == null) {
            // A bare UTC designator ("Z") with no bracketed annotation names an exact instant, not a
            // wall-clock date/date-time - unlike a plain numeric offset (which a relativeTo string
            // discards), "Z" alone is invalid as relativeTo.
            if ("Z".equalsIgnoreCase(parsed.offset())) {
                throw new RangeErrorException(
                        "relativeTo string with a UTC designator requires a bracketed time zone annotation: " + text);
            }
            return RelativeDurationMath.Anchor.plain(date, time);
        }
        final var timeZoneId = TemporalParser.parseTimeZoneIdentifier(parsed.timeZoneId());
        final var zone = TemporalZonedDateTimeBuiltins.zoneOf(timeZoneId);
        if (parsed.offset() != null && !"Z".equalsIgnoreCase(parsed.offset())) {
            requireMatchingOffset(date, time, zone, parsed.offset(), text);
        }
        return RelativeDurationMath.Anchor.zoned(date, time, zone);
    }

    // A relativeTo string carrying both an explicit numeric offset and a bracketed zone annotation
    // must agree - unlike Temporal.ZonedDateTime.from (which trusts the given offset outright for
    // exactness), relativeTo has no `offset` disambiguation option, so a mismatch is simply invalid.
    private static void requireMatchingOffset(Iso8601Fields date, IsoTimeFields time, java.time.ZoneId zone,
            String offsetText, String source) {
        final java.time.ZoneOffset given;
        try {
            var normalized = offsetText;
            final var dot = normalized.indexOf('.');
            final var comma = normalized.indexOf(',');
            final var fractionStart = dot < 0 ? comma : (comma < 0 ? dot : Math.min(dot, comma));
            if (fractionStart >= 0) {
                normalized = normalized.substring(0, fractionStart);
            }
            given = java.time.ZoneOffset.of(normalized);
        } catch (java.time.DateTimeException e) {
            throw new RangeErrorException("Invalid UTC offset: " + offsetText);
        }
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final java.time.LocalDateTime local;
        try {
            local = java.time.LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(),
                    time.second(), nanoOfSecond);
        } catch (java.time.DateTimeException e) {
            throw new RangeErrorException("Invalid date/time in relativeTo: " + e.getMessage());
        }
        final var actual = zone.getRules().getOffset(local);
        if (!given.equals(actual)) {
            throw new RangeErrorException(
                    "relativeTo string offset " + offsetText + " does not match time zone " + zone + ": " + source);
        }
    }

    // A `calendar` property that is itself a Temporal object (PlainDate/PlainDateTime/PlainYearMonth/
    // PlainMonthDay/ZonedDateTime) is taken via its fast path - its calendar is implicitly "iso8601"
    // in this ISO-only engine, so its `calendar`/`calendarId` getters are never read (matching
    // ToTemporalCalendar's own internal-slot fast path). Any other value must coerce to "iso8601".
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
        TemporalCalendarIdentifier.canonicalizeFlexible(s.getValue());
    }

    private static RelativeDurationMath.Anchor relativeToFromFields(JsValue obj, InterpreterOps ops) {
        requireValidCalendarField(obj, ops);
        final var year = requiredRelativeToField(obj, "year", ops);
        final var month = resolveRelativeToMonth(obj, ops);
        final var day = requiredRelativeToField(obj, "day", ops);
        final var hour = relativeToFieldOrDefault(obj, "hour", 0, ops);
        final var minute = relativeToFieldOrDefault(obj, "minute", 0, ops);
        final var second = relativeToFieldOrDefault(obj, "second", 0, ops);
        final var millisecond = relativeToFieldOrDefault(obj, "millisecond", 0, ops);
        final var microsecond = relativeToFieldOrDefault(obj, "microsecond", 0, ops);
        final var nanosecond = relativeToFieldOrDefault(obj, "nanosecond", 0, ops);
        final var date = IsoCalendar.regulateDate(year, month, day, RegulateOverflow.CONSTRAIN);
        final var time = new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
        final var timeZoneRaw = ops.getMember(obj, new JsString("timeZone"));
        if (timeZoneRaw instanceof JsUndefined) {
            return RelativeDurationMath.Anchor.plain(date, time);
        }
        if (!(timeZoneRaw instanceof JsString s)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        final var timeZoneId = TemporalParser.parseTimeZoneIdentifierFlexible(s.getValue());
        return RelativeDurationMath.Anchor.zoned(date, time, TemporalZonedDateTimeBuiltins.zoneOf(timeZoneId));
    }

    private static int requiredRelativeToField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required in a relativeTo fields object");
        }
        return relativeToIntegerField(value, name, ops);
    }

    private static int relativeToFieldOrDefault(JsValue obj, String name, int defaultValue, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        return value instanceof JsUndefined ? defaultValue : relativeToIntegerField(value, name, ops);
    }

    private static int resolveRelativeToMonth(JsValue obj, InterpreterOps ops) {
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        final var monthValue = ops.getMember(obj, new JsString("month"));
        if (!(monthCodeValue instanceof JsUndefined)) {
            final var resolved = parseRelativeToMonthCode(JsCoercion.toStr(monthCodeValue, ops));
            if (!(monthValue instanceof JsUndefined) && relativeToIntegerField(monthValue, "month", ops) != resolved) {
                throw new RangeErrorException("month and monthCode are inconsistent");
            }
            return resolved;
        }
        if (monthValue instanceof JsUndefined) {
            throw new TypeErrorException("month or monthCode is required in a relativeTo fields object");
        }
        return relativeToIntegerField(monthValue, "month", ops);
    }

    private static int parseRelativeToMonthCode(String code) {
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

    // ToIntegerWithTruncation: a finite number is required, truncated toward zero.
    // ToIntegerWithTruncation, per TemporalRoundingIncrement's own spec algorithm - unlike a Duration
    // field or a relativeTo field-object value, roundingIncrement is truncated rather than required to
    // already be an integer (2.5 is accepted and truncates to 2).
    private static long roundingIncrementValue(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException("roundingIncrement must be a finite integer, got " + number);
        }
        return (long) (number < 0 ? Math.ceil(number) : Math.floor(number));
    }

    private static int relativeToIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException(name + " must be a finite integer, got " + number);
        }
        final var truncated = number < 0 ? Math.ceil(number) : Math.floor(number);
        return (int) truncated;
    }

    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsString str) {
            final var fields = TemporalParser.parseDuration(str.getValue());
            DurationMath.sign(fields);
            return fields;
        }
        if (InterpreterUtils.isObjectLike(value)) {
            return durationLikeFields(value, ops);
        }
        throw new TypeErrorException(
                "Expected a Temporal.Duration, an ISO 8601 duration string, or a duration-like object");
    }

    public static JsValue getMethod(JsTemporalDuration receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), ops));
            case "negated" -> new JsNativeFunction("negated", (_, _) -> negated(receiver));
            case "abs" -> new JsNativeFunction("abs", (_, _) -> abs(receiver));
            case "add" -> new JsNativeFunction("add", (_, args) -> addOrSubtract(receiver, arg(args, 0), ops, false));
            case "subtract" ->
                new JsNativeFunction("subtract", (_, args) -> addOrSubtract(receiver, arg(args, 0), ops, true));
            case "round" -> new JsNativeFunction("round", (_, args) -> round(receiver, arg(args, 0), ops));
            case "total" -> new JsNativeFunction("total", (_, args) -> total(receiver, arg(args, 0), ops));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException("Cannot convert a Temporal.Duration to a primitive value");
            });
            default -> null;
        };
    }

    // The 12 accessor getters (10 fields + sign + blank), dispatched from Intrinsics' per-name
    // accessor loop rather than getMethod above.
    public static JsValue fieldAccessor(JsTemporalDuration receiver, String name) {
        final var f = receiver.getFields();
        return switch (name) {
            case "years" -> new JsNumber(f.years());
            case "months" -> new JsNumber(f.months());
            case "weeks" -> new JsNumber(f.weeks());
            case "days" -> new JsNumber(f.days());
            case "hours" -> new JsNumber(f.hours());
            case "minutes" -> new JsNumber(f.minutes());
            case "seconds" -> new JsNumber(f.seconds());
            case "milliseconds" -> new JsNumber(f.milliseconds());
            case "microseconds" -> new JsNumber(f.microseconds());
            case "nanoseconds" -> new JsNumber(f.nanoseconds());
            case "sign" -> new JsNumber(receiver.sign());
            case "blank" -> JsBoolean.of(receiver.blank());
            default -> JsUndefined.getInstance();
        };
    }

    private static JsValue with(JsTemporalDuration receiver, JsValue durationLike, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(durationLike)) {
            throw new TypeErrorException("Temporal.Duration.prototype.with requires a duration-like object");
        }
        final var current = receiver.getFields();
        final var currentValues = new double[]{current.years(), current.months(), current.weeks(), current.days(),
                current.hours(), current.minutes(), current.seconds(), current.milliseconds(), current.microseconds(),
                current.nanoseconds()};
        var anyPresent = false;
        for (final var name : PROPERTY_READ_ORDER) {
            final var member = ops.getMember(durationLike, new JsString(name));
            if (member != null && !(member instanceof JsUndefined)) {
                currentValues[FIELD_ORDER.indexOf(name)] = integerValue(member, ops);
                anyPresent = true;
            }
        }
        if (!anyPresent) {
            throw new TypeErrorException("Duration-like object must contain at least one recognized property");
        }
        final var fields = toFields(currentValues);
        DurationMath.sign(fields);
        return new JsTemporalDuration(fields);
    }

    private static JsValue negated(JsTemporalDuration receiver) {
        return new JsTemporalDuration(DurationMath.negate(receiver.getFields()));
    }

    private static JsValue abs(JsTemporalDuration receiver) {
        final var f = receiver.getFields();
        return new JsTemporalDuration(new DurationFields(Math.abs(f.years()), Math.abs(f.months()), Math.abs(f.weeks()),
                Math.abs(f.days()), Math.abs(f.hours()), Math.abs(f.minutes()), Math.abs(f.seconds()),
                Math.abs(f.milliseconds()), Math.abs(f.microseconds()), Math.abs(f.nanoseconds())));
    }

    // AddDurations, narrowed to the calendar-independent case. The two operands' nanosecond totals
    // are summed (not their raw fields - a signed per-field sum like {days:1} + {hours:-1} can
    // legitimately produce mixed-sign fields before carrying, which balanceDuration's own uniform-
    // sign check would reject) and the signed total is then decomposed back into fields. Any nonzero
    // year/month/week on either operand needs a relativeTo date to resolve and is rejected instead of
    // guessed.
    private static JsValue addOrSubtract(JsTemporalDuration receiver, JsValue otherArg, InterpreterOps ops,
            boolean subtract) {
        final var a = receiver.getFields();
        final var other = toDurationFields(otherArg, ops);
        try {
            DurationMath.requireCalendarIndependent(a, Unit.DAY);
            DurationMath.requireCalendarIndependent(other, Unit.DAY);
            final var otherTotal = DurationMath.totalNanoseconds(other);
            final var totalNanos = DurationMath.totalNanoseconds(a).add(subtract ? otherTotal.negate() : otherTotal);
            // The result balances no further than the coarser of the two operands' own largest
            // day-and-below unit (e.g. adding to a pure-hours duration never introduces a "days"
            // field) - never further than day, per requireCalendarIndependent above.
            final var largestOrdinal = Math.min(tailLargestUnitForAdd(a).ordinal(),
                    tailLargestUnitForAdd(other).ordinal());
            return new JsTemporalDuration(
                    DurationMath.balanceFromTotalNanoseconds(totalNanos, Unit.values()[largestOrdinal]));
        } catch (UnsupportedOperationException e) {
            // add()/subtract() with a calendar-unit operand is out of this feature's scope (unlike
            // round/total/compare/since/until, add/subtract's relativeTo-anchored balancing was not
            // part of the closed gap - see the feature plan's scope note).
            throw new RangeErrorException(RELATIVE_TO_REQUIRED);
        }
    }

    private static JsValue round(JsTemporalDuration receiver, JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg == null || optionsArg instanceof JsUndefined) {
            throw new TypeErrorException("Temporal.Duration.prototype.round requires an options argument");
        }
        final var options = optionsArg instanceof JsString unit ? singleKeyOptions("smallestUnit", unit) : optionsArg;
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a unit string");
        }
        final var smallestUnitValue = ops.getMember(options, new JsString("smallestUnit"));
        final var largestUnitValue = ops.getMember(options, new JsString("largestUnit"));
        final var incrementValue = ops.getMember(options, new JsString("roundingIncrement"));
        final var modeValue = ops.getMember(options, new JsString("roundingMode"));
        final var relativeToValue = ops.getMember(options, new JsString("relativeTo"));
        if (isAbsent(smallestUnitValue) && isAbsent(largestUnitValue)) {
            throw new RangeErrorException("round requires at least one of smallestUnit or largestUnit");
        }
        final var fields = receiver.getFields();
        final var smallestUnit = isAbsent(smallestUnitValue)
                ? Unit.NANOSECOND
                : Unit.parseTemporalUnit(JsCoercion.toStr(smallestUnitValue, ops));
        final var largestUnit = resolveLargestUnit(largestUnitValue, fields, smallestUnit, ops);
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        final var increment = isAbsent(incrementValue) ? 1 : roundingIncrementValue(incrementValue, ops);
        if (increment < 1 || increment > 1_000_000_000) {
            throw new RangeErrorException("roundingIncrement out of range: " + increment);
        }
        final var mode = isAbsent(modeValue)
                ? RoundingMode.HALF_EXPAND
                : RoundingMode.parse(JsCoercion.toStr(modeValue, ops));
        if (!isAbsent(relativeToValue)) {
            final var anchor = toRelativeToAnchor(relativeToValue, ops);
            validateIncrementForBalancing(smallestUnit, largestUnit, increment);
            validateRoundingIncrementForUnit(increment, smallestUnit);
            final var endPoint = RelativeDurationMath.applyDuration(anchor, fields, RegulateOverflow.CONSTRAIN);
            // Only roundedDifference below resolves the endpoint through RelativeDurationMath's own
            // exact-instant range check; the plain differenceCalendar fast path never does, so a
            // calendar-part addition that lands outside Temporal's representable range (huge
            // weeks/days) would otherwise go undetected. Discarding the result: this call's only
            // purpose here is that range check.
            RelativeDurationMath.toEpochNanos(anchor, endPoint.date(), endPoint.time());
            final var result = smallestUnit == Unit.NANOSECOND && increment == 1
                    ? DurationMath.differenceCalendar(anchor.date(), anchor.time(), endPoint.date(), endPoint.time(),
                            largestUnit)
                    : RelativeDurationMath.roundedDifference(anchor, endPoint.date(), endPoint.time(), largestUnit,
                            smallestUnit, increment, mode);
            return new JsTemporalDuration(result);
        }
        if (hasCalendarUnits(fields) || largestUnit.isLargerThan(Unit.DAY) || smallestUnit.isLargerThan(Unit.DAY)) {
            throw new RangeErrorException(RELATIVE_TO_REQUIRED);
        }
        return new JsTemporalDuration(DurationMath.roundDuration(fields, smallestUnit, increment, mode, largestUnit));
    }

    // largestUnit's "auto" (or absent) default is LargerOfTwoTemporalUnits(DefaultTemporalLargestUnit
    // (duration), smallestUnit) - the largest unit already present in the duration, widened to
    // smallestUnit if that is coarser still (e.g. a {days:364} duration rounded to "years" needs
    // largestUnit to widen to "years" too, not stay at the duration's own natural "days").
    private static Unit resolveLargestUnit(JsValue largestUnitValue, DurationFields fields, Unit smallestUnit,
            InterpreterOps ops) {
        final var autoDefault = coarserOf(defaultLargestUnit(fields), smallestUnit);
        if (isAbsent(largestUnitValue)) {
            return autoDefault;
        }
        final var raw = JsCoercion.toStr(largestUnitValue, ops);
        return "auto".equals(raw) ? autoDefault : Unit.parseTemporalUnit(raw);
    }

    private static Unit defaultLargestUnit(DurationFields f) {
        if (f.years() != 0) {
            return Unit.YEAR;
        }
        if (f.months() != 0) {
            return Unit.MONTH;
        }
        if (f.weeks() != 0) {
            return Unit.WEEK;
        }
        if (f.days() != 0) {
            return Unit.DAY;
        }
        if (f.hours() != 0) {
            return Unit.HOUR;
        }
        if (f.minutes() != 0) {
            return Unit.MINUTE;
        }
        if (f.seconds() != 0) {
            return Unit.SECOND;
        }
        if (f.milliseconds() != 0) {
            return Unit.MILLISECOND;
        }
        if (f.microseconds() != 0) {
            return Unit.MICROSECOND;
        }
        return Unit.NANOSECOND;
    }

    private static Unit coarserOf(Unit a, Unit b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    // RoundDuration disallows a rounding increment greater than 1 for a date unit (year/month/week/
    // day) when also balancing to a DIFFERENT (coarser) largestUnit - the ambiguity of "round to N
    // months, then re-express in years" isn't well-defined the way "round to N months, stay in
    // months" is. increment 1, or largestUnit == smallestUnit, are both always fine.
    private static void validateIncrementForBalancing(Unit smallestUnit, Unit largestUnit, long increment) {
        if (smallestUnit.ordinal() <= Unit.DAY.ordinal() && increment != 1 && largestUnit != smallestUnit) {
            throw new RangeErrorException("roundingIncrement > 1 is not supported for smallestUnit \""
                    + smallestUnit.singular() + "\" when balancing to a different largestUnit");
        }
    }

    // Date units (year/month/week/day) accept any increment in range (validateIncrementForBalancing
    // above covers their one real restriction); time units must divide evenly into their natural cycle
    // length, matching every other Temporal type's rounding-increment validation.
    private static void validateRoundingIncrementForUnit(long increment, Unit unit) {
        if (unit.ordinal() <= Unit.DAY.ordinal()) {
            return;
        }
        final var maximum = switch (unit) {
            case HOUR -> 24;
            case MINUTE, SECOND -> 60;
            case MILLISECOND, MICROSECOND, NANOSECOND -> 1000;
            default -> throw new RangeErrorException("Invalid unit for rounding: " + unit);
        };
        if (maximum % increment != 0 || increment == maximum) {
            throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit " + unit.singular());
        }
    }

    private static JsValue total(JsTemporalDuration receiver, JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg == null || optionsArg instanceof JsUndefined) {
            throw new TypeErrorException("Temporal.Duration.prototype.total requires an options argument");
        }
        final var options = optionsArg instanceof JsString unit ? singleKeyOptions("unit", unit) : optionsArg;
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a unit string");
        }
        final var unitValue = ops.getMember(options, new JsString("unit"));
        if (isAbsent(unitValue)) {
            throw new RangeErrorException("total requires a unit option");
        }
        final var unit = Unit.parseTemporalUnit(JsCoercion.toStr(unitValue, ops));
        final var relativeToValue = ops.getMember(options, new JsString("relativeTo"));
        final var fields = receiver.getFields();
        if (!isAbsent(relativeToValue)) {
            final var anchor = toRelativeToAnchor(relativeToValue, ops);
            final var endPoint = RelativeDurationMath.applyDuration(anchor, fields, RegulateOverflow.CONSTRAIN);
            return new JsNumber(RelativeDurationMath.totalInUnit(anchor, endPoint.date(), endPoint.time(), unit));
        }
        if (hasCalendarUnits(fields) || unit.isLargerThan(Unit.DAY)) {
            throw new RangeErrorException(RELATIVE_TO_REQUIRED);
        }
        final var totalNanos = new BigDecimal(DurationMath.totalNanoseconds(fields));
        final var perUnit = new BigDecimal(DurationMath.nanosPerUnit(unit));
        // 50 significant digits (MathContext, not a fixed post-point scale) leaves an enormous margin
        // over a double's ~17, so the BigDecimal -> double conversion below is exact regardless of this
        // intermediate rounding mode's tie-breaking choice - see RelativeDurationMath.exactDivide.
        return new JsNumber(totalNanos.divide(perUnit, new java.math.MathContext(50)).doubleValue());
    }

    private static JsValue toStringMethod(JsTemporalDuration receiver, JsValue optionsArg, InterpreterOps ops) {
        if (isAbsent(optionsArg)) {
            return new JsString(receiver.toString());
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("Temporal.Duration.prototype.toString options must be an object");
        }
        // Each option is fully read AND coerced (observably calling valueOf/toString) one at a time,
        // in this fixed order - fractionalSecondDigits, roundingMode, smallestUnit - before any of the
        // three is used algorithmically (smallestUnit, if present, overrides fractionalSecondDigits
        // for the actual rounding, but fractionalSecondDigits is still read+coerced regardless).
        final var digitsValue = ops.getMember(optionsArg, new JsString("fractionalSecondDigits"));
        Integer fractionalSecondDigits = isAbsent(digitsValue) ? null : parseFractionalDigits(digitsValue, ops);
        final var roundingModeValue = ops.getMember(optionsArg, new JsString("roundingMode"));
        final var mode = isAbsent(roundingModeValue)
                ? RoundingMode.TRUNC
                : RoundingMode.parse(JsCoercion.toStr(roundingModeValue, ops));
        final var smallestUnitValue = ops.getMember(optionsArg, new JsString("smallestUnit"));
        var toFormat = receiver.getFields();
        if (!isAbsent(smallestUnitValue)) {
            final var unit = parseFractionalUnit(JsCoercion.toStr(smallestUnitValue, ops));
            toFormat = roundFractionalTail(toFormat, unit, mode, 1);
            fractionalSecondDigits = digitsForUnit(unit);
        } else if (fractionalSecondDigits != null) {
            final var increment = (long) Math.pow(10, 9 - fractionalSecondDigits);
            toFormat = roundFractionalTail(toFormat, Unit.NANOSECOND, mode, increment);
        }
        return new JsString(TemporalFormatter.formatDuration(toFormat, fractionalSecondDigits));
    }

    // toString's smallestUnit is always second-or-finer (parseFractionalUnit enforces this), so
    // rounding it only ever needs to touch the day-and-below tail - years/months/weeks stay exactly
    // as they are, never being consulted or reset by the rounding itself. Splitting the tail out
    // before rounding (rather than rounding the whole DurationFields) is what lets a Duration with a
    // nonzero year/month/week format with a smallestUnit/fractionalSecondDigits option at all: the
    // calendar-independent DurationMath.roundDuration used here rejects any nonzero year/month/week
    // outright, and reattaching them post-round keeps that check meaningful elsewhere.
    private static DurationFields roundFractionalTail(DurationFields fields, Unit unit, RoundingMode mode,
            long roundingIncrement) {
        final var tail = new DurationFields(0, 0, 0, fields.days(), fields.hours(), fields.minutes(), fields.seconds(),
                fields.milliseconds(), fields.microseconds(), fields.nanoseconds());
        final var rounded = DurationMath.roundDuration(tail, unit, roundingIncrement, mode, tailLargestUnit(fields));
        return new DurationFields(fields.years(), fields.months(), fields.weeks(), rounded.days(), rounded.hours(),
                rounded.minutes(), rounded.seconds(), rounded.milliseconds(), rounded.microseconds(),
                rounded.nanoseconds());
    }

    // A rounding-induced carry only reaches as far as the coarsest unit already present in the
    // day-and-below tail (e.g. a lone "PT59.9S" rounds to "PT60S", not "PT1M0S", while "PT1H59M59.9S"
    // carries all the way to "PT2H0S") - never further than day, and never coarser than second.
    private static Unit tailLargestUnit(DurationFields fields) {
        if (fields.days() != 0) {
            return Unit.DAY;
        }
        if (fields.hours() != 0) {
            return Unit.HOUR;
        }
        if (fields.minutes() != 0) {
            return Unit.MINUTE;
        }
        return Unit.SECOND;
    }

    // Like tailLargestUnit, but for AddDurations' balance-no-further-than-necessary logic: unlike
    // round()'s carry destination (which never needs to distinguish "nothing populated" from "only
    // seconds-and-below populated" - both round to a second-or-coarser result anyway), an add() whose
    // operands are both purely sub-second (e.g. two microsecond-only durations, as produced by
    // ZonedDateTime.prototype.since with largestUnit "microseconds") must balance no further than
    // whichever of second/millisecond/microsecond is actually the coarsest populated field -
    // defaulting to "second" here would wrongly re-decompose the summed total down through seconds
    // first, discarding almost all of the sub-second magnitude into a spurious seconds field - see
    // ZonedDateTime/prototype/since/float64-representable-integer.js.
    private static Unit tailLargestUnitForAdd(DurationFields fields) {
        if (fields.days() != 0) {
            return Unit.DAY;
        }
        if (fields.hours() != 0) {
            return Unit.HOUR;
        }
        if (fields.minutes() != 0) {
            return Unit.MINUTE;
        }
        if (fields.seconds() != 0) {
            return Unit.SECOND;
        }
        if (fields.milliseconds() != 0) {
            return Unit.MILLISECOND;
        }
        if (fields.microseconds() != 0) {
            return Unit.MICROSECOND;
        }
        return Unit.NANOSECOND;
    }

    private static Unit parseFractionalUnit(String value) {
        final var unit = Unit.parseTemporalUnit(value);
        if (unit != Unit.SECOND && unit != Unit.MILLISECOND && unit != Unit.MICROSECOND && unit != Unit.NANOSECOND) {
            throw new RangeErrorException(
                    "smallestUnit must be one of seconds/milliseconds/microseconds/nanoseconds, got " + value);
        }
        return unit;
    }

    private static int digitsForUnit(Unit unit) {
        return switch (unit) {
            case SECOND -> 0;
            case MILLISECOND -> 3;
            case MICROSECOND -> 6;
            default -> 9;
        };
    }

    // GetStringOrNumberOption: a value that isn't already type Number is converted via ToString and
    // must equal "auto" exactly (never falls back to ToNumber); a Number is floored rather than
    // rejected for being non-integer (e.g. 2.5 floors to 2, -0.6 floors to -1 and is then out of range).
    private static Integer parseFractionalDigits(JsValue value, InterpreterOps ops) {
        if (!(value instanceof JsNumber number)) {
            final var str = JsCoercion.toStr(value, ops);
            if (!"auto".equals(str)) {
                throw new RangeErrorException(JsCoercion.toStr(value) + " is not a number and converts to the string '"
                        + str + "' which is not valid for fractionalSecondDigits");
            }
            return null;
        }
        final var raw = number.getValue();
        if (!Double.isFinite(raw)) {
            throw new RangeErrorException("fractionalSecondDigits must be 0-9 or \"auto\", got " + raw);
        }
        final var floored = Math.floor(raw);
        if (floored < 0 || floored > 9) {
            throw new RangeErrorException(
                    "fractionalSecondDigits " + raw + " floors to " + (long) floored + " and is out of range");
        }
        return (int) floored;
    }

    // A null-prototype object (OrdinaryObjectCreate(null)) so a lookup of an absent option key (e.g.
    // relativeTo, when only a unit string shorthand was passed) never falls through to Object.prototype.
    private static JsObject singleKeyOptions(String key, JsString value) {
        final var options = new JsObject();
        options.setProto(null);
        options.set(key, value);
        return options;
    }

    private static boolean isAbsent(JsValue value) {
        return value == null || value instanceof JsUndefined;
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}

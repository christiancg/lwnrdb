package org.techhouse.simplejs.builtins;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
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
import org.techhouse.simplejs.values.JsTemporalPlainTime;
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

    // TemporalMonthCode syntax: an uppercase "M", two digits, and an optional leap-month "L" suffix.
    // Syntax is validated at read time (see monthCodeSyntaxChecked); numeric-range/leap suitability
    // is validated later, only once the calendar has all the fields it needs - see monthCodeValue.
    private static final Pattern MONTH_CODE_SYNTAX = Pattern.compile("M\\d{2}L?");

    // ISOYearMonthWithinLimits: the representable range is independent of the referenceISODay, so a
    // PlainYearMonth's own year/month combination is checked without regard to any particular day.
    private static final int MIN_YEAR = -271_821;
    private static final int MIN_YEAR_MIN_MONTH = 4;
    private static final int MAX_YEAR = 275_760;
    private static final int MAX_YEAR_MAX_MONTH = 9;

    private static final IsoTimeFields MIDNIGHT = new IsoTimeFields(0, 0, 0, 0, 0, 0);

    private TemporalPlainYearMonthBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainYearMonth", (thisArg, args) -> {
            requireNewTarget(thisArg);
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

    // Unlike Map/Set/Date (always reached as a bare global identifier, so a plain call's thisArg is
    // reliably undefined), Temporal.PlainYearMonth only ever exists as a member of the Temporal
    // namespace object - so a plain `Temporal.PlainYearMonth()` call's thisArg is that namespace
    // object, not undefined, and a bare "thisArg is not undefined" check would wrongly accept it. A
    // genuine subclass super() call is told apart instead by instance provenance: ClassEvaluator
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
        throw new TypeErrorException("Constructor Temporal.PlainYearMonth requires 'new'");
    }

    // OrdinaryCreateFromConstructor: Reflect.construct(Temporal.PlainYearMonth, args, Ctor) links the
    // new instance's [[Prototype]] to Ctor.prototype rather than always to the intrinsic prototype.
    private static JsValue withNewTargetPrototype(JsTemporalPlainYearMonth constructed, InterpreterOps ops) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (ops == null || newTarget == null || newTarget instanceof JsUndefined) {
            return constructed;
        }
        final var proto = ops.getMember(newTarget, new JsString("prototype"));
        if (!(proto instanceof JsObject requested) || proto == ops.getPrototypeOf(constructed)) {
            return constructed;
        }
        final var wrapper = new JsObject();
        wrapper.setPrimitive(constructed);
        wrapper.setProto(requested);
        return wrapper;
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

    // Constructor argument: a bare calendar identifier only; a non-string value is a TypeError.
    private static void requireCalendarString(JsValue calendarArg) {
        if (!(calendarArg instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        TemporalCalendarIdentifier.canonicalizeBare(s.getValue());
    }

    // ISOYearMonthWithinLimits: April -271821 through September 275760, independent of the day.
    private static void requireYearMonthInRange(int year, int month) {
        if (year < MIN_YEAR || year > MAX_YEAR || (year == MIN_YEAR && month < MIN_YEAR_MIN_MONTH)
                || (year == MAX_YEAR && month > MAX_YEAR_MAX_MONTH)) {
            throw new RangeErrorException(
                    "year-month " + year + "-" + month + " is outside the representable range of PlainYearMonth");
        }
    }

    // The calendar arithmetic below (add/subtract/until/since) constructs a real day-1 ISO date as an
    // internal pivot; that date must be within Temporal's representable PlainDate range, even though
    // the PlainYearMonth value itself - which never exposes this pivot day - can be perfectly valid on
    // its own (e.g. the minimum PlainYearMonth's default day 1 precedes the minimum representable
    // date of day 19). This is deliberately NOT the exact-Instant nanosecond range (see
    // RelativeDurationMath.MAX_INSTANT_NANOS, whose exact boundary is one day narrower on the min side
    // - the min Instant is -271821-04-20T00:00Z, but the min PlainDate is one day earlier still, per
    // PlainDate/limits.js): a plain calendar date's own representable range is the well-known
    // -271821-04-19 .. +275760-09-13 span, confirmed exactly by that same PlainDate limits test.
    private static final long MIN_PLAIN_DATE_EPOCH_DAY = -100_000_001L;
    private static final long MAX_PLAIN_DATE_EPOCH_DAY = 100_000_000L;

    private static void requireDateInRange(Iso8601Fields date) {
        final long epochDay;
        try {
            epochDay = LocalDate.of(date.year(), date.month(), date.day()).toEpochDay();
        } catch (DateTimeException e) {
            throw new RangeErrorException("date value is outside the representable range: " + date);
        }
        if (epochDay < MIN_PLAIN_DATE_EPOCH_DAY || epochDay > MAX_PLAIN_DATE_EPOCH_DAY) {
            throw new RangeErrorException("date value is outside the representable range: " + date);
        }
    }

    private static JsTemporalPlainYearMonth toPlainYearMonth(JsValue item, InterpreterOps ops) {
        return toPlainYearMonth(item, JsUndefined.getInstance(), ops);
    }

    // ToTemporalYearMonth: an existing PlainYearMonth/other Temporal type and a string both read (and
    // discard) the `overflow` option for its side effect before their own dispatch; a plain fields
    // object instead prepares its fields first and only reads `overflow` at the very end (as part of
    // resolving them) - see from/order-of-operations.js and from/observable-get-overflow-argument-
    // string-invalid.js, which pin down exactly this asymmetry. A value that is neither an object nor
    // a string never touches options at all.
    private static JsTemporalPlainYearMonth toPlainYearMonth(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        if (item instanceof JsTemporalPlainYearMonth ym) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainYearMonth(ym.fields());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainYearMonth wrapped) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainYearMonth(wrapped.fields());
        }
        // ToTemporalYearMonth reads a Temporal object argument's year/month fields the same way it
        // reads a plain object's (PrepareTemporalFields calls Get, not an internal-slot bypass), but
        // these Temporal types are not JsObject, so they need their own branches here to reach the
        // same year+month result (referenceISODay forced to 1, per CalendarYearMonthFromFields).
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
            // A syntactically invalid string must never touch `options` at all - parse (and validate
            // its calendar annotation) completely before reading `overflow` for its side effect.
            final var parsed = TemporalParser.parseYearMonth(s.getValue());
            if (parsed.calendar() != null) {
                TemporalCalendarIdentifier.canonicalizeBare(parsed.calendar());
            }
            // Per CalendarYearMonthFromFields, the iso8601 calendar always normalises referenceISODay
            // to 1 - even when the source string was a full date/date-time carrying a real day-of-month
            // (e.g. "2019-12-15T00+00"), not just the reduced "YYYY-MM" form. `overflow` is still read
            // (for its Get side effect) even though the parsed result is never regulated by it.
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

    // CalendarYearMonthFromFields, PrepareTemporalFields order: "month" and "monthCode" are always
    // fetched (regardless of which is used) before "year" is even checked for presence - see
    // from/missing-properties.js. Only once year is confirmed present is the month value actually
    // resolved (its syntax was already checked at read time, but a monthCode/month conflict or an
    // out-of-range monthCode is validated only now - see from/monthcode-invalid.js) and is `overflow`
    // read, matching from/order-of-operations.js exactly.
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

    // Property-bag `calendar` field: accepts a bare identifier or a full ISO string carrying (or
    // defaulting) a u-ca annotation. A `calendar` that is itself a Temporal object is taken via its
    // fast path (its calendar is implicitly "iso8601" in this ISO-only engine, so its
    // `calendar`/`calendarId` getters are never read, matching ToTemporalCalendar's own
    // internal-slot fast path).
    private static void requireValidCalendarField(JsValue obj, InterpreterOps ops) {
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

    private static int requiredDayField(JsValue obj, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString("day"));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException("day is required");
        }
        return toIntegerField(value, "day", ops);
    }

    // ToPositiveIntegerWithTruncation: "month" must truncate to a strictly positive integer regardless
    // of the `overflow` option - a non-positive value is always a RangeError, even under overflow
    // "constrain" (which only clamps the *upper* bound against the calendar). See
    // from/negative-month.js and from/overflow-constrain.js.
    private static int requirePositiveMonthField(JsValue value, InterpreterOps ops) {
        final var truncated = toIntegerField(value, "month", ops);
        if (truncated < 1) {
            throw new RangeErrorException("month must be a positive integer, got " + truncated);
        }
        return truncated;
    }

    // TemporalMonthCode field: must literally be (or ToPrimitive-resolve to, hint "string") a String -
    // a non-string primitive result (e.g. a number returned by a custom toString) is a TypeError, not
    // further coerced. Only the syntax (uppercase M, two digits, optional leap L) is validated here;
    // numeric-range/leap suitability is deferred to monthCodeValue. See from/month-code-wrong-type.js
    // and from/monthcode-invalid.js.
    private static String monthCodeSyntaxChecked(JsValue value, InterpreterOps ops) {
        if (value instanceof JsUndefined) {
            return null;
        }
        final var primitive = InterpreterUtils.isObjectLike(value)
                ? JsCoercion.toPrimitive(value, "string", ops)
                : value;
        if (!(primitive instanceof JsString s)) {
            throw new TypeErrorException("monthCode must be a string");
        }
        final var code = s.getValue();
        if (!MONTH_CODE_SYNTAX.matcher(code).matches()) {
            throw new RangeErrorException("Invalid monthCode: " + code);
        }
        return code;
    }

    // The numeric month a syntactically-valid monthCode denotes for the iso8601 calendar, which never
    // has leap months - an "L" suffix is always out of range here, however well-formed its syntax.
    private static int monthCodeValue(String code) {
        final var leap = code.endsWith("L");
        final var value = Integer.parseInt(code.substring(1, 3));
        if (leap || value < 1 || value > 12) {
            throw new RangeErrorException("monthCode " + code + " is not valid for the ISO 8601 calendar");
        }
        return value;
    }

    private static int resolveMonthValue(Integer month, String monthCode) {
        if (monthCode != null) {
            final var resolved = monthCodeValue(monthCode);
            if (month != null && month != resolved) {
                throw new RangeErrorException("month and monthCode are inconsistent");
            }
            return resolved;
        }
        if (month != null) {
            return month;
        }
        throw new TypeErrorException("month or monthCode is required");
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

    // Only the Get + ToString coercion happens at read time; the "must be year or month" restriction
    // is deferred to resolveLargestUnit/resolveSmallestUnit so all four difference() options are read
    // before any of them is algorithmically validated - see since/options-read-before-algorithmic-
    // validation.js (a disallowed largestUnit must not skip reading roundingIncrement/roundingMode/
    // smallestUnit).
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

    // Deferred (see readUnitOptionRaw's note): the 1..1e9 bound is validated only after every
    // difference() option has been read.
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

    // IsPartialTemporalObject rejects any of the six built-in Temporal types outright (even another
    // PlainYearMonth) - with()'s argument must be a plain fields-like object, never a Temporal
    // instance. See with/yearmonthlike-invalid.js.
    private static boolean isTemporalLikeObject(JsValue value) {
        final var unwrapped = value instanceof JsObject wrapper ? wrapper.getPrimitive() : value;
        return unwrapped instanceof JsTemporalPlainDate || unwrapped instanceof JsTemporalPlainDateTime
                || unwrapped instanceof JsTemporalPlainMonthDay || unwrapped instanceof JsTemporalPlainTime
                || unwrapped instanceof JsTemporalPlainYearMonth || unwrapped instanceof JsTemporalZonedDateTime;
    }

    // with(): RejectObjectWithCalendarOrTimeZone always reads (and rejects on) `calendar`/`timeZone`
    // first, then PrepareTemporalFields reads month/monthCode/year (alphabetical, each coerced
    // immediately) - all before `overflow` is read. A month/monthCode conflict or an argument with
    // none of year/month/monthCode is a TypeError/RangeError only resolved at the end, mirroring
    // from()'s own field resolution. See with/order-of-operations.js and with/yearmonthlike-invalid.js.
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

    // PrepareTemporalFields reads duration-like properties in alphabetical order, each coerced
    // immediately at Get time - see prototype/add/order-of-operations.js.
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

    // Temporal.PlainYearMonth only understands year/month calendar arithmetic - any nonzero
    // week/day/time component is unconditionally a RangeError, regardless of the `overflow` option,
    // but only once `overflow` has already been read for its side effect. See
    // prototype/add/argument-lower-units.js and prototype/add/options-read-before-algorithmic-
    // validation.js.
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

    // GetDifferenceSettings reads its four options in alphabetical order (largestUnit,
    // roundingIncrement, roundingMode, smallestUnit) fully before the smallestUnit/largestUnit
    // ordering is validated - see prototype/since/options-read-before-algorithmic-validation.js. A
    // receiver equal to `other` short-circuits to a zero duration before ever constructing either
    // side's day-1 pivot date, which is why minYearMonth.since(minYearMonth) never triggers the
    // representable-range check that minYearMonth.since(anythingElse) does - see prototype/since/
    // throws-if-year-outside-valid-iso-range.js.
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

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}

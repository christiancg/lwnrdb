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
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * {@code Temporal.PlainDate}: an "iso8601"-calendar-only calendar date (see the feature plan's
 * scope-defining finding).
 */
public final class TemporalPlainDateBuiltins {
    public static final List<String> NAMES = List.of("with", "withCalendar", "add", "subtract", "until", "since",
            "equals", "toPlainYearMonth", "toPlainMonthDay", "toPlainDateTime", "toZonedDateTime", "toString", "toJSON",
            "toLocaleString", "getISOFields", "valueOf");

    private record UnresolvedDateFields(int year, Integer month, String monthCode, int day) {
    }

    private TemporalPlainDateBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainDate", (_, args) -> {
            if (JsNativeFunction.currentNewTarget() == null) {
                throw new TypeErrorException("Constructor Temporal.PlainDate requires 'new'");
            }
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

    // OrdinaryCreateFromConstructor via Reflect.construct(Temporal.PlainDate, args, newTarget): links
    // the constructed instance's proto to newTarget.prototype (and propagates a poisoned prototype
    // getter) when it differs from the intrinsic one. Unlike a `class X extends Temporal.PlainDate`
    // super() call - whose new.target threading is a separate, documented, unimplemented gap - a
    // Reflect.construct-supplied newTarget is genuinely available here via currentNewTarget().
    private static JsValue withNewTargetPrototype(JsTemporalPlainDate constructed, InterpreterOps ops) {
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

    public static JsValue getMethod(JsTemporalPlainDate receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
            case "withCalendar" ->
                new JsNativeFunction("withCalendar", (_, args) -> withCalendar(receiver, arg(args, 0), ops));
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

    // `disposed`-style accessor install (mirrors DisposableStackBuiltins.installAccessors): these are
    // real accessor properties on the shared prototype, brand-checked per-call since they are not
    // routed through Intrinsics' requireTemporalPlainDate resolver the way NAMES methods are.
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

    private static void installGetter(JsObject proto, String name, Function<JsValue, JsValue> impl) {
        final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> impl.apply(thisArg));
        getter.setLength(0);
        proto.defineAccessor(name, getter, null);
        proto.setFlags(name, new JsObject.PropertyFlags(true, false, true));
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
            requireCalendarString(calendarArg, ops);
        }
        return new JsTemporalPlainDate(IsoCalendar.regulateDate(year, month, day, RegulateOverflow.REJECT));
    }

    // Constructor: accepts only a bare calendar identifier (no ISO date-time-string extraction
    // fallback); a non-string value is a TypeError, not a RangeError.
    private static String requireCalendarString(JsValue calendarArg, InterpreterOps ops) {
        if (!(calendarArg instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        return TemporalCalendarIdentifier.canonicalizeBare(s.getValue());
    }

    // ToTemporalDate: accepts an existing PlainDate, an ISO date string, or a date-like object
    // (year + month/monthCode + day, regulated per the `overflow` option). For every branch, the
    // conversion-specific work happens BEFORE `options` is ever read (fields for the object branch,
    // ParseISODateTime for the string branch, nothing extra for the fast-path clones) - options is
    // only read+validated afterward (its value is unused for the fast paths, but it must still be
    // read/validated so a bad options argument is observably rejected in the right order).
    private static JsTemporalPlainDate toPlainDate(JsValue item, InterpreterOps ops) {
        return toPlainDate(item, JsUndefined.getInstance(), ops);
    }

    private static JsTemporalPlainDate toPlainDate(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        if (item instanceof JsTemporalPlainDate pd) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainDate(pd.fields());
        }
        // ToTemporalDate's fast paths for other Temporal types carrying an ISO date: their date
        // fields are taken directly (PlainDateTime) or via the zone's local wall-clock reading
        // (ZonedDateTime), without going through the generic fields-object / calendar-field path.
        if (item instanceof JsTemporalPlainDateTime dt) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainDate(dt.date());
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainDate(zdt.isoFieldsAtLocal().date());
        }
        if (item instanceof JsString s) {
            final var parsed = TemporalParser.parseDate(s.getValue());
            if (parsed.calendar() != null) {
                TemporalCalendarIdentifier.canonicalizeBare(parsed.calendar());
            }
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainDate(parsed.date());
        }
        // A Proxy (or any other non-JsObject object-like value) must still be walked field-by-field
        // through `ops` rather than requiring a concrete JsObject - an `instanceof JsObject` check
        // here would silently treat a Proxy fields bag as unconvertible.
        if (InterpreterUtils.isObjectLike(item)) {
            final var fields = resolveDateFields(item, ops);
            // The month/monthCode suitability-and-consistency check is "algorithmic validation" -
            // deferred until after `options` has been fully read, even though the raw fields
            // themselves are read first (see from/options-read-before-algorithmic-validation.js).
            final var overflow = readOverflowOption(optionsArg, ops);
            final var month = resolveMonthFromFields(fields.month(), fields.monthCode());
            return new JsTemporalPlainDate(IsoCalendar.regulateDate(fields.year(), month, fields.day(), overflow));
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.PlainDate");
    }

    // ISODateFromFields: fields are read (and Cast) in alphabetical order - day, month, monthCode,
    // year - matching PrepareCalendarFields, so a TypeError for a missing required field (day/year)
    // or a wrong-typed monthCode fires before a later field's RangeError (see
    // calendarresolvefields-error-ordering.js / monthcode-invalid.js). Suitability/consistency
    // between month and monthCode is resolved separately by the caller, once `options` has been read.
    private static UnresolvedDateFields resolveDateFields(JsValue obj, InterpreterOps ops) {
        requireValidCalendarField(obj, ops);
        final var day = requiredPositiveIntegerField(obj, "day", ops);
        final var month = optionalPositiveIntegerField(obj, "month", ops);
        final var monthCode = optionalMonthCodeField(obj, ops);
        final var year = requiredIntegerField(obj, "year", ops);
        return new UnresolvedDateFields(year, month, monthCode, day);
    }

    // Property-bag `calendar` field: accepts a bare identifier or a full ISO string carrying (or
    // defaulting) a u-ca annotation. A `calendar` that is itself a Temporal object is taken via its
    // fast path (its calendar is implicitly "iso8601" in this ISO-only engine, so its
    // `calendar`/`calendarId` getters are never read, matching ToTemporalCalendarIdentifier's own
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

    private static int requiredIntegerField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required");
        }
        return toIntegerField(value, name, ops);
    }

    private static int requiredPositiveIntegerField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required");
        }
        return toPositiveIntegerField(value, name, ops);
    }

    private static Integer optionalPositiveIntegerField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        return value instanceof JsUndefined ? null : toPositiveIntegerField(value, name, ops);
    }

    // monthCode's Cast is ToPrimitive(value, "string") followed by a STRICT typeof-string check (not
    // a full ToString) - an object whose toString()/valueOf() resolves to a non-string primitive (or
    // any other non-string primitive: number, bigint, boolean, symbol, null) is a TypeError, never
    // silently stringified. A syntactically well-formed but semantically unsuitable code (wrong
    // digits, or the ISO-unsupported leap-month `L` suffix) is a separate, later RangeError - kept
    // apart here so a garbled non-`M...` shape fails before a sibling field's later Cast, while a
    // syntactically valid-but-unsuitable code fails after (see monthcode-invalid.js).
    private static String optionalMonthCodeField(JsValue obj, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString("monthCode"));
        return value instanceof JsUndefined ? null : requireMonthCodeValue(value, ops);
    }

    private static String requireMonthCodeValue(JsValue value, InterpreterOps ops) {
        final var primitive = JsCoercion.toPrimitive(value, "string", ops);
        if (!(primitive instanceof JsString s)) {
            throw new TypeErrorException("monthCode must be a string");
        }
        requireMonthCodeSyntax(s.getValue());
        return s.getValue();
    }

    // Generic TemporalMonthCode syntax: "M" + 2 digits + an optional leap-month "L" suffix. Purely
    // shape validation - whether the numeric part / the `L` suffix is actually SUITABLE for the
    // iso8601 calendar (which has no leap months) is checked later, only once every sibling field of
    // the fields-object has been read (see monthCodeSuitabilityForIso).
    private static void requireMonthCodeSyntax(String code) {
        final var length = code.length();
        if ((length != 3 && length != 4) || code.charAt(0) != 'M' || !Character.isDigit(code.charAt(1))
                || !Character.isDigit(code.charAt(2)) || (length == 4 && code.charAt(3) != 'L')) {
            throw new RangeErrorException("Invalid monthCode: " + code);
        }
    }

    // ISO 8601 never has leap months, so any `L`-suffixed code is unsuitable regardless of its
    // numeric part; otherwise the numeric part must be a real month (1..12).
    private static int monthCodeSuitabilityForIso(String code) {
        final var value = Integer.parseInt(code.substring(1, 3));
        if (code.length() == 4 || value < 1 || value > 12) {
            throw new RangeErrorException("monthCode is not valid for the iso8601 calendar: " + code);
        }
        return value;
    }

    private static int resolveMonthFromFields(Integer month, String monthCode) {
        if (monthCode != null) {
            final var fromCode = monthCodeSuitabilityForIso(monthCode);
            if (month != null && month != fromCode) {
                throw new RangeErrorException("month and monthCode are inconsistent");
            }
            return fromCode;
        }
        if (month != null) {
            return month;
        }
        throw new TypeErrorException("month or monthCode is required");
    }

    // ToIntegerWithTruncation: a finite number is required, truncated toward zero (a genuinely
    // integral value, e.g. 12, passes through unchanged).
    private static int toIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException(name + " must be a finite integer, got " + number);
        }
        return (int) number;
    }

    // ToPositiveIntegerWithTruncation: like toIntegerField, but the truncated result must also be
    // >= 1 - independent of any `overflow` option, so month/day are rejected as soon as they are
    // read even under overflow "constrain" (see with/overflow.js, from/negative-month-or-day.js).
    private static int toPositiveIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var result = toIntegerField(value, name, ops);
        if (result < 1) {
            throw new RangeErrorException(name + " must be a positive integer, got " + result);
        }
        return result;
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

    // A raw (Cast-only) read of a string-shaped option: fetch + ToString, with no further validation
    // - the "is this actually a valid/allowed value" check is deferred by the caller until every
    // option has been read (see difference()'s options-read-before-algorithmic-validation handling).
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

    // with()'s argument resolution: reject a Temporal instance / an object carrying a calendar or
    // timeZone property first, then read day/month/monthCode/year (alphabetical, defaulting to the
    // receiver's own field when absent) - at least one must be present - and only then read
    // `overflow` (see with/order-of-operations.js, with/plaindatelike-invalid.js, with/overflow.js).
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
        // As in resolveDateFields()/toPlainDate(), month/monthCode suitability-and-consistency is
        // "algorithmic validation" deferred until after `options` is read (see
        // with/options-read-before-algorithmic-validation.js).
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = monthPresent || monthCodePresent
                ? resolveMonthFromFields(month, monthCode)
                : receiver.month();
        return new JsTemporalPlainDate(IsoCalendar.regulateDate(year, resolvedMonth, day, overflow));
    }

    // RejectTemporalLikeObject: with()'s argument must not itself be one of the eight built-in
    // Temporal instance types (a plain data bag is required, not another Temporal value).
    private static void rejectTemporalLikeObject(JsValue value) {
        if (value instanceof JsTemporalPlainDate || value instanceof JsTemporalPlainDateTime
                || value instanceof JsTemporalPlainMonthDay || value instanceof JsTemporalPlainTime
                || value instanceof JsTemporalPlainYearMonth || value instanceof JsTemporalZonedDateTime
                || value instanceof JsTemporalDuration || value instanceof JsTemporalInstant) {
            throw new TypeErrorException("Temporal.PlainDate.prototype.with argument must not be a Temporal object");
        }
    }

    // RejectObjectWithCalendarOrTimeZone: reads (and rejects on) `calendar` first, then `timeZone` -
    // regardless of value, merely HAVING either property disqualifies the argument.
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

    // withCalendar is effectively an identity operation in ISO-only mode: the only calendar this
    // engine ever carries is "iso8601". Unlike the constructor's bare-identifier-only calendar
    // argument, ToTemporalCalendarIdentifier here accepts either a fast-path Temporal object (read via
    // instanceof only - its calendar is implicitly "iso8601", so no property is ever read from it) or
    // any full ISO date/date-time/time/year-month/month-day string (a bare identifier is one degenerate
    // case of that grammar).
    private static JsValue withCalendar(JsTemporalPlainDate receiver, JsValue calendarArg, InterpreterOps ops) {
        if (!(calendarArg instanceof JsTemporalPlainDate || calendarArg instanceof JsTemporalPlainDateTime
                || calendarArg instanceof JsTemporalPlainMonthDay || calendarArg instanceof JsTemporalPlainYearMonth
                || calendarArg instanceof JsTemporalZonedDateTime)) {
            if (!(calendarArg instanceof JsString s)) {
                throw new TypeErrorException("calendar must be a string");
            }
            TemporalCalendarIdentifier.canonicalizeFlexible(s.getValue());
        }
        return new JsTemporalPlainDate(receiver.fields());
    }

    // ToTemporalDuration: a duration-like object's ten fields are Cast in alphabetical order - days,
    // hours, microseconds, milliseconds, minutes, months, nanoseconds, seconds, weeks, years - not
    // DurationRecord's own largest-to-smallest order (see add/order-of-operations.js).
    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsString s) {
            return TemporalParser.parseDuration(s.getValue());
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

    // ToIntegerIfIntegral: unlike the date fields above, a Duration-like field must already BE an
    // integer (no truncation) - 1.5 is a RangeError, not silently floored. A missing property
    // returns null (rather than defaulting to 0 here) so the caller can reject a duration-like value
    // that has none of the ten recognized properties present.
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

    // AddDate: a duration's time units (hours..nanoseconds) are balanced into whole days - truncated
    // toward zero, via the duration's exact nanosecond total - and folded into the days field before
    // the calendar-aware years/months/weeks/days arithmetic; the calendar's dateAdd operation never
    // reads a duration's time units directly (see add/balance-smaller-units*.js).
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

    private static final IsoTimeFields MIDNIGHT = new IsoTimeFields(0, 0, 0, 0, 0, 0);

    // GetDifferenceSettings: `other` is fully converted (with no options of its own) before `options`
    // is even validated; then largestUnit, roundingIncrement, roundingMode and smallestUnit are all
    // read and Cast, in that alphabetical order, BEFORE any algorithmic validation (unit family,
    // smallestUnit/largestUnit ordering, increment range) is attempted (see
    // since/order-of-operations.js, until/options-read-before-algorithmic-validation.js).
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

    // CalendarYearMonthFromFields: referenceISODay is always forced to 1, regardless of the source
    // date's own day (see toPlainYearMonth/basic.js, toPlainYearMonth/limits.js).
    private static JsValue toPlainYearMonth(JsTemporalPlainDate receiver) {
        return new JsTemporalPlainYearMonth(new Iso8601Fields(receiver.year(), receiver.month(), 1));
    }

    // CalendarMonthDayFromFields: referenceISOYear is always forced to 1972 (the ISO default),
    // regardless of the source date's own year (see toPlainMonthDay/basic.js).
    private static JsValue toPlainMonthDay(JsTemporalPlainDate receiver) {
        return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, receiver.month(), receiver.day()));
    }

    // ISODateWithinLimits already guarantees the date part is representable (see
    // IsoCalendar.regulateDate); PlainDateTime's own range is narrower by exactly one instant at the
    // very bottom - midnight on PlainDate's own minimum date is the sole combination that falls
    // outside the true +-8.64e21ns instant envelope once combined with a time-of-day (every other
    // date/time-of-day combination admitted by the date-only check stays inside it) - see
    // toPlainDateTime/limits.js.
    private static final Iso8601Fields MIN_PLAIN_DATE = new Iso8601Fields(-271821, 4, 19);

    private static JsValue toPlainDateTime(JsTemporalPlainDate receiver, JsValue timeLike, InterpreterOps ops) {
        final var time = extractTimeFields(timeLike, ops);
        if (receiver.fields().equals(MIN_PLAIN_DATE) && time.equals(MIDNIGHT)) {
            throw new RangeErrorException("date value is outside the representable range for Temporal.PlainDateTime");
        }
        return new JsTemporalPlainDateTime(receiver.fields(), time);
    }

    // ToTemporalTimeRecord (as used for a plainTime-like argument to toPlainDateTime/toZonedDateTime):
    // fields are read in alphabetical order - hour, microsecond, millisecond, minute, nanosecond,
    // second - each regulated ("constrain": clamped into its valid range, so an out-of-range or leap
    // second is silently clamped rather than rejected) and at least one must be present when an object
    // is given explicitly (see toPlainDateTime/order-of-operations.js, .../leap-second.js,
    // .../plaintime-propertybag-no-time-units.js). A value omitted entirely (argument undefined)
    // defaults to midnight without this restriction.
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
        final var zone = TemporalZonedDateTimeBuiltins.zoneOf(timeZoneId);
        return TemporalZonedDateTimeBuiltins.resolveToZoned(receiver.fields(), time, zone, timeZoneId,
                TemporalZonedDateTimeBuiltins.Disambiguation.COMPATIBLE);
    }

    private static String extractTimeZoneId(JsValue options, InterpreterOps ops) {
        if (options instanceof JsString s) {
            return TemporalParser.parseTimeZoneIdentifierFlexible(s.getValue());
        }
        // A Proxy options bag (or any other non-JsObject object-like value) must still be consulted
        // via `ops` - an `instanceof JsObject` check here would silently skip straight to "requires a
        // timeZone" for a Proxy, never reading its `timeZone` property at all.
        if (InterpreterUtils.isObjectLike(options)) {
            final var timeZone = ops.getMember(options, new JsString("timeZone"));
            if (timeZone instanceof JsString s) {
                return TemporalParser.parseTimeZoneIdentifierFlexible(s.getValue());
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

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}

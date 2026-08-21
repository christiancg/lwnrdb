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
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * {@code Temporal.PlainMonthDay}: a thin, "iso8601"-calendar-only projection of {@code PlainDate}
 * onto month+day (see the feature plan's scope-defining finding). Unlike {@code PlainYearMonth},
 * this type has no calendar arithmetic at all per spec - a bare month+day cannot be added to or
 * differenced against a duration without a year to anchor it, so there is deliberately no
 * {@code add}/{@code subtract}/{@code until}/{@code since}, no {@code compare} static, and no
 * numeric {@code month} accessor (only {@code monthCode}, since a bare month number is ambiguous
 * without a year in a general calendar - kept for symmetry with the wider Temporal type family even
 * though it makes no practical difference for the ISO-only calendar this engine implements).
 */
public final class TemporalPlainMonthDayBuiltins {
    public static final List<String> NAMES = List.of("with", "equals", "toPlainDate", "toString", "toJSON",
            "toLocaleString", "getISOFields", "valueOf");

    // Shared with PlainYearMonth's own copy: TemporalMonthCode syntax is an uppercase "M", two
    // digits, and an optional leap-month "L" suffix. Syntax is validated at read time; numeric-range/
    // leap suitability is deferred until every field has been read - see monthCodeValue.
    private static final Pattern MONTH_CODE_SYNTAX = Pattern.compile("M\\d{2}L?");

    // The representable PlainDate range (-271821-04-19 .. +275760-09-13, confirmed exactly by
    // PlainDate/limits.js) - unlike PlainYearMonth, PlainMonthDay's `from`/`with` fields path never
    // range-checks against this (the referenceISOYear it resolves to is always forced to 1972, a
    // value comfortably inside the range); only the raw constructor's explicit referenceISOYear
    // argument and toPlainDate's real, caller-supplied year can actually leave this range.
    private static final long MIN_PLAIN_DATE_EPOCH_DAY = -100_000_001L;
    private static final long MAX_PLAIN_DATE_EPOCH_DAY = 100_000_000L;

    private TemporalPlainMonthDayBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainMonthDay", (_, args) -> {
            requireNewTarget();
            return withNewTargetPrototype(construct(args, ops), ops);
        });
        ctor.setLength(2);
        final var from = new JsNativeFunction("from", (_, args) -> toPlainMonthDay(arg(args, 0), arg(args, 1), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        return ctor;
    }

    public static JsValue getMethod(JsTemporalPlainMonthDay receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
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
                        "Cannot convert a Temporal.PlainMonthDay to a primitive value with valueOf; use equals() "
                                + "instead");
            });
            default -> null;
        };
    }

    public static void installAccessors(JsObject proto) {
        installGetter(proto, "monthCode",
                receiver -> new JsString(monthCode(requireReceiver(receiver, "monthCode").month())));
        installGetter(proto, "day", receiver -> new JsNumber(requireReceiver(receiver, "day").day()));
        installGetter(proto, "calendarId", receiver -> {
            requireReceiver(receiver, "calendarId");
            return new JsString("iso8601");
        });
    }

    private static void installGetter(JsObject proto, String name, Function<JsValue, JsValue> impl) {
        final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> impl.apply(thisArg));
        getter.setLength(0);
        proto.defineAccessor(name, getter, null);
        proto.setFlags(name, new JsObject.PropertyFlags(true, false, true));
    }

    private static JsTemporalPlainMonthDay requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalPlainMonthDay md) {
            return md;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainMonthDay wrapped) {
            return wrapped;
        }
        throw new TypeErrorException(
                "Temporal.PlainMonthDay.prototype." + method + " called on an incompatible receiver");
    }

    private static void requireNewTarget() {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (newTarget == null || newTarget instanceof JsUndefined) {
            throw new TypeErrorException("Constructor Temporal.PlainMonthDay requires 'new'");
        }
    }

    // OrdinaryCreateFromConstructor: Reflect.construct(Temporal.PlainMonthDay, args, Ctor) links the
    // new instance's [[Prototype]] to Ctor.prototype rather than always to the intrinsic prototype.
    private static JsValue withNewTargetPrototype(JsTemporalPlainMonthDay constructed, InterpreterOps ops) {
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

    // Unlike from()/with() (whose referenceISOYear is always forced to 1972, comfortably in range),
    // the raw constructor accepts an explicit referenceISOYear that can genuinely land the resulting
    // ISO date outside the representable PlainDate range - see refisoyear-out-of-range.js.
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

    private static JsTemporalPlainMonthDay construct(List<JsValue> args, InterpreterOps ops) {
        final var month = toIntegerField(arg(args, 0), "month", ops);
        final var day = toIntegerField(arg(args, 1), "day", ops);
        final var calendarArg = arg(args, 2);
        if (!(calendarArg instanceof JsUndefined)) {
            requireCalendarString(calendarArg);
        }
        final var referenceISOYearArg = arg(args, 3);
        final var referenceISOYear = referenceISOYearArg instanceof JsUndefined
                ? JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR
                : toIntegerField(referenceISOYearArg, "referenceISOYear", ops);
        final var result = IsoCalendar.regulateDate(referenceISOYear, month, day, RegulateOverflow.REJECT);
        requireDateInRange(result);
        return new JsTemporalPlainMonthDay(result);
    }

    // Constructor argument: a bare calendar identifier only; a non-string value is a TypeError.
    private static void requireCalendarString(JsValue calendarArg) {
        if (!(calendarArg instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        TemporalCalendarIdentifier.canonicalizeBare(s.getValue());
    }

    private static JsTemporalPlainMonthDay toPlainMonthDay(JsValue item, InterpreterOps ops) {
        return toPlainMonthDay(item, JsUndefined.getInstance(), ops);
    }

    // ToTemporalMonthDay: an existing PlainMonthDay/other Temporal type and a string both read (and
    // discard) the `overflow` option for its side effect before their own dispatch; a plain fields
    // object instead prepares its fields first and only reads `overflow` at the very end (as part of
    // resolving them) - mirrors PlainYearMonth's own toPlainYearMonth exactly, see from/order-of-
    // operations.js and from/observable-get-overflow-argument-string-invalid.js.
    private static JsTemporalPlainMonthDay toPlainMonthDay(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        if (item instanceof JsTemporalPlainMonthDay md) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainMonthDay(md.fields());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainMonthDay wrapped) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainMonthDay(wrapped.fields());
        }
        // As with PlainYearMonth, these Temporal types are not JsObject, so they need their own
        // branches here; referenceISOYear is always forced to 1972 regardless of the source's actual
        // year (CalendarMonthDayFromFields discards it, keeping it only for leap-day validity, which
        // is moot here since the source date is already a valid concrete date).
        if (item instanceof JsTemporalPlainDate pd) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, pd.month(), pd.day()));
        }
        if (item instanceof JsTemporalPlainDateTime dt) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, dt.month(), dt.day()));
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            readOverflowOption(optionsArg, ops);
            final var date = zdt.isoFieldsAtLocal().date();
            return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, date.month(), date.day()));
        }
        if (item instanceof JsString s) {
            // A syntactically invalid string must never touch `options` at all - parse (and validate
            // its calendar annotation) completely before reading `overflow` for its side effect.
            final var parsed = TemporalParser.parseMonthDay(s.getValue());
            if (parsed.calendar() != null) {
                TemporalCalendarIdentifier.canonicalizeBare(parsed.calendar());
            }
            // `overflow` is still read (for its Get side effect) per ToTemporalMonthDay, even though
            // the parsed result is never regulated by it. Per CalendarMonthDayFromFields, the
            // iso8601 calendar always normalises referenceISOYear to 1972 - even when the source
            // string was a full date/date-time carrying a real year (e.g. "2000-05-02T00+00"), not
            // just the reduced "MM-DD"/"--MM-DD" form.
            readOverflowOption(optionsArg, ops);
            final var date = parsed.date();
            return new JsTemporalPlainMonthDay(
                    new Iso8601Fields(JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR, date.month(), date.day()));
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return monthDayFromFields(item, optionsArg, ops);
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.PlainMonthDay");
    }

    // CalendarMonthDayFromFields, PrepareTemporalFields order: "day", "month" and "monthCode" are
    // always fetched (regardless of which is used) before "day" is even checked for presence - see
    // from/calendarresolvefields-error-ordering.js ("day is required" must win over a later monthCode
    // RangeError). "year" is read last and, unlike PlainYearMonth, is never required - it is used only
    // to resolve the `overflow` option (e.g. leap-day validity) and is then discarded: the final
    // referenceISOYear is always 1972, regardless of what year (if any) was supplied - see from/
    // iso-year-used-only-for-overflow.js.
    private static JsTemporalPlainMonthDay monthDayFromFields(JsValue obj, JsValue optionsArg, InterpreterOps ops) {
        requireValidCalendarField(obj, ops);
        final var dayValue = ops.getMember(obj, new JsString("day"));
        final var day = dayValue instanceof JsUndefined
                ? null
                : (Integer) requirePositiveIntegerField(dayValue, "day", ops);
        final var monthValue = ops.getMember(obj, new JsString("month"));
        final var month = monthValue instanceof JsUndefined
                ? null
                : (Integer) requirePositiveIntegerField(monthValue, "month", ops);
        final var monthCode = monthCodeSyntaxChecked(ops.getMember(obj, new JsString("monthCode")), ops);
        final var yearValue = ops.getMember(obj, new JsString("year"));
        final var year = yearValue instanceof JsUndefined
                ? JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR
                : toIntegerField(yearValue, "year", ops);
        if (day == null) {
            throw new TypeErrorException("day is required");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = resolveMonthValue(month, monthCode);
        final var regulated = IsoCalendar.regulateDate(year, resolvedMonth, day, overflow);
        return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, regulated.month(), regulated.day()));
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

    private static int requiredYearField(JsValue obj, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString("year"));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException("year is required");
        }
        return toIntegerField(value, "year", ops);
    }

    // ToPositiveIntegerWithTruncation: "month" and "day" must truncate to a strictly positive integer
    // regardless of the `overflow` option - see from/negative-month-or-day.js.
    private static int requirePositiveIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var truncated = toIntegerField(value, name, ops);
        if (truncated < 1) {
            throw new RangeErrorException(name + " must be a positive integer, got " + truncated);
        }
        return truncated;
    }

    // TemporalMonthCode field: must literally be (or ToPrimitive-resolve to, hint "string") a String;
    // only the syntax is validated here, numeric-range/leap suitability is deferred to
    // monthCodeValue - see from/monthcode-invalid.js.
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
    // PlainMonthDay) - with()'s argument must be a plain fields-like object, never a Temporal
    // instance. See with/monthdaylike-invalid.js.
    private static boolean isTemporalLikeObject(JsValue value) {
        final var unwrapped = value instanceof JsObject wrapper ? wrapper.getPrimitive() : value;
        return unwrapped instanceof JsTemporalPlainDate || unwrapped instanceof JsTemporalPlainDateTime
                || unwrapped instanceof JsTemporalPlainMonthDay || unwrapped instanceof JsTemporalPlainTime
                || unwrapped instanceof JsTemporalPlainYearMonth || unwrapped instanceof JsTemporalZonedDateTime;
    }

    // with(): RejectObjectWithCalendarOrTimeZone always reads (and rejects on) `calendar`/`timeZone`
    // first, then PrepareTemporalFields reads day/month/monthCode/year (alphabetical, each coerced
    // immediately) - all before `overflow` is read. The receiver's own fields (including its actual
    // referenceISOYear, which for a from()/with()-built value is always 1972) are the merge defaults;
    // the final result's referenceISOYear is nonetheless always forced back to 1972 - see with/order-
    // of-operations.js and with/iso-year-used-only-for-overflow.js.
    private static JsValue with(JsTemporalPlainMonthDay receiver, JsValue fieldsLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(fieldsLike) || isTemporalLikeObject(fieldsLike)) {
            throw new TypeErrorException("Temporal.PlainMonthDay.prototype.with argument must be an object");
        }
        final var calendarValue = ops.getMember(fieldsLike, new JsString("calendar"));
        if (!(calendarValue instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a calendar property");
        }
        final var timeZoneValue = ops.getMember(fieldsLike, new JsString("timeZone"));
        if (!(timeZoneValue instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a timeZone property");
        }
        final var dayValue = ops.getMember(fieldsLike, new JsString("day"));
        final var day = dayValue instanceof JsUndefined
                ? null
                : (Integer) requirePositiveIntegerField(dayValue, "day", ops);
        final var monthValue = ops.getMember(fieldsLike, new JsString("month"));
        final var month = monthValue instanceof JsUndefined
                ? null
                : (Integer) requirePositiveIntegerField(monthValue, "month", ops);
        final var monthCode = monthCodeSyntaxChecked(ops.getMember(fieldsLike, new JsString("monthCode")), ops);
        final var yearValue = ops.getMember(fieldsLike, new JsString("year"));
        final var year = yearValue instanceof JsUndefined ? null : (Integer) toIntegerField(yearValue, "year", ops);
        if (day == null && month == null && monthCode == null && year == null) {
            throw new TypeErrorException("with() argument must contain at least one of year, month, monthCode, day");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = month == null && monthCode == null
                ? receiver.month()
                : resolveMonthValue(month, monthCode);
        final var resolvedDay = day != null ? day : receiver.day();
        final var resolvedYear = year != null ? year : receiver.referenceISOYear();
        final var regulated = IsoCalendar.regulateDate(resolvedYear, resolvedMonth, resolvedDay, overflow);
        return new JsTemporalPlainMonthDay(new Iso8601Fields(1972, regulated.month(), regulated.day()));
    }

    // equals compares the receiver's actual [[ISODate]] slot in full (month, day AND
    // referenceISOYear) - two PlainMonthDays built from different reference years are not equal,
    // mirroring CompareISODate.
    private static JsValue equalsMethod(JsTemporalPlainMonthDay receiver, JsValue otherArg, InterpreterOps ops) {
        final var other = toPlainMonthDay(otherArg, ops);
        return JsBoolean.of(IsoCalendar.compareIsoDate(receiver.fields(), other.fields()) == 0);
    }

    private static JsValue toPlainDate(JsTemporalPlainMonthDay receiver, JsValue item, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(item)) {
            throw new TypeErrorException(
                    "Temporal.PlainMonthDay.prototype.toPlainDate requires an object with a year property");
        }
        final var year = requiredYearField(item, ops);
        final var result = IsoCalendar.regulateDate(year, receiver.month(), receiver.day(), RegulateOverflow.CONSTRAIN);
        requireDateInRange(result);
        return new JsTemporalPlainDate(result);
    }

    private static JsValue toStringMethod(JsTemporalPlainMonthDay receiver, JsValue optionsArg, InterpreterOps ops) {
        final var calendarName = readCalendarNameOption(optionsArg, ops);
        return new JsString(TemporalFormatter.formatMonthDay(receiver.fields(), calendarName));
    }

    private static JsValue getISOFields(JsTemporalPlainMonthDay receiver) {
        final var obj = new JsObject();
        obj.set("calendar", new JsString("iso8601"));
        obj.set("isoDay", new JsNumber(receiver.day()));
        obj.set("isoMonth", new JsNumber(receiver.month()));
        obj.set("isoYear", new JsNumber(receiver.referenceISOYear()));
        return obj;
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}

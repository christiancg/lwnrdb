package org.techhouse.simplejs.builtins;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
 * {@code Temporal.PlainDateTime}: the union of {@code Temporal.PlainDate}'s and {@code
 * Temporal.PlainTime}'s method surface plus {@code toZonedDateTime}. {@link #toPlainYearMonth}/
 * {@link #toPlainMonthDay} return real {@code Temporal.PlainYearMonth}/{@code PlainMonthDay}
 * instances (T4, merged alongside this phase). {@link #toZonedDateTime} returns a real {@code
 * Temporal.ZonedDateTime} (T7), resolving the local date-time to an instant per the
 * {@code disambiguation} option.
 */
public final class TemporalPlainDateTimeBuiltins {
    public static final List<String> NAMES = List.of("with", "withCalendar", "withPlainTime", "add", "subtract",
            "until", "since", "round", "equals", "toPlainDate", "toPlainTime", "toPlainYearMonth", "toPlainMonthDay",
            "toZonedDateTime", "toString", "toJSON", "toLocaleString", "getISOFields", "valueOf");

    private static final BigInteger NANOS_PER_DAY = BigInteger.valueOf(86_400_000_000_000L);
    private static final BigInteger NANOS_PER_HOUR = BigInteger.valueOf(3_600_000_000_000L);
    private static final BigInteger NANOS_PER_MINUTE = BigInteger.valueOf(60_000_000_000L);
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger NANOS_PER_MILLI = BigInteger.valueOf(1_000_000L);
    private static final BigInteger NANOS_PER_MICRO = BigInteger.valueOf(1_000L);
    // The Instant epoch-nanoseconds limit (+/-8.64e21, i.e. +/-100_000_000 days from the epoch).
    // PlainDateTime's own representable range is exactly one day wider on each side (it has no
    // associated offset, so the boundary day itself is only partially valid - see requireWithinLimits).
    private static final BigInteger INSTANT_EPOCH_NANOS_LIMIT = BigInteger.valueOf(864)
            .multiply(BigInteger.TEN.pow(19));
    private static final long NANOS_PER_HOUR_LONG = 3_600_000_000_000L;
    private static final long NANOS_PER_MINUTE_LONG = 60_000_000_000L;
    private static final long NANOS_PER_SECOND_LONG = 1_000_000_000L;
    private static final long NANOS_PER_MILLI_LONG = 1_000_000L;
    private static final long NANOS_PER_MICRO_LONG = 1_000L;
    private static final BigInteger TWO = BigInteger.valueOf(2);

    private TemporalPlainDateTimeBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainDateTime", (_, args) -> {
            requireNewTarget();
            return withNewTargetPrototype(construct(args, ops), ops);
        });
        ctor.setLength(3);
        final var from = new JsNativeFunction("from", (_, args) -> toDateTime(arg(args, 0), arg(args, 1), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare", (_, args) -> new JsNumber(
                JsTemporalPlainDateTime.compare(toDateTime(arg(args, 0), ops), toDateTime(arg(args, 1), ops))));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        return ctor;
    }

    // Same narrow requireNewTarget shape as TemporalPlainDateBuiltins: Temporal.PlainDateTime is only
    // ever reached as a member of the Temporal namespace, so a plain call's thisArg is that namespace
    // object rather than undefined - newTarget alone tells a genuine `new`/Reflect.construct call
    // apart (a documented gap: a subclass's super() call is not supported).
    private static void requireNewTarget() {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (newTarget == null || newTarget instanceof JsUndefined) {
            throw new TypeErrorException("Constructor Temporal.PlainDateTime requires 'new'");
        }
    }

    // OrdinaryCreateFromConstructor: Reflect.construct(Temporal.PlainDateTime, args, Ctor) links the
    // new instance's [[Prototype]] to Ctor.prototype rather than always to the intrinsic prototype
    // (mirrors TemporalPlainTimeBuiltins.withNewTargetPrototype) - and, crucially, the Get(newTarget,
    // "prototype") itself must run so a poisoned prototype getter's exception propagates.
    private static JsValue withNewTargetPrototype(JsTemporalPlainDateTime constructed, InterpreterOps ops) {
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

    private static JsTemporalPlainDateTime construct(List<JsValue> args, InterpreterOps ops) {
        final var year = toIntegerField(arg(args, 0), "year", ops);
        final var month = toIntegerField(arg(args, 1), "month", ops);
        final var day = toIntegerField(arg(args, 2), "day", ops);
        final var hour = intOrZero(args, 3, ops);
        final var minute = intOrZero(args, 4, ops);
        final var second = intOrZero(args, 5, ops);
        final var millisecond = intOrZero(args, 6, ops);
        final var microsecond = intOrZero(args, 7, ops);
        final var nanosecond = intOrZero(args, 8, ops);
        final var calendarArg = arg(args, 9);
        if (!(calendarArg instanceof JsUndefined)) {
            requireCalendarString(calendarArg);
        }
        final var date = IsoCalendar.regulateDate(year, month, day, RegulateOverflow.REJECT);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond,
                RegulateOverflow.REJECT);
        return dateTime(date, time);
    }

    private static int intOrZero(List<JsValue> args, int index, InterpreterOps ops) {
        if (index >= args.size() || args.get(index) instanceof JsUndefined) {
            return 0;
        }
        return toIntegerField(args.get(index), "field", ops);
    }

    // ToIntegerWithTruncation: a finite number is required, truncated toward zero.
    private static int toIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException(name + " must be a finite integer, got " + number);
        }
        final var truncated = number < 0 ? Math.ceil(number) : Math.floor(number);
        return (int) truncated;
    }

    private static IsoTimeFields regulateTime(int hour, int minute, int second, int millisecond, int microsecond,
            int nanosecond, RegulateOverflow overflow) {
        if (overflow == RegulateOverflow.CONSTRAIN) {
            return new IsoTimeFields(Math.clamp(hour, 0, 23), Math.clamp(minute, 0, 59), Math.clamp(second, 0, 59),
                    Math.clamp(millisecond, 0, 999), Math.clamp(microsecond, 0, 999), Math.clamp(nanosecond, 0, 999));
        }
        validateRange("hour", hour, 23);
        validateRange("minute", minute, 59);
        validateRange("second", second, 59);
        validateRange("millisecond", millisecond, 999);
        validateRange("microsecond", microsecond, 999);
        validateRange("nanosecond", nanosecond, 999);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    private static void validateRange(String name, int value, int max) {
        if (value < 0 || value > max) {
            throw new RangeErrorException(name + " must be in the range 0.." + max + ", got " + value);
        }
    }

    // ISODateTimeWithinLimits: exclusive at both ends, one day wider than Instant's own +/-8.64e21ns
    // range - e.g. -271821-04-19T00:00:00 is invalid but one nanosecond later is valid, while
    // -271821-04-20T00:00:00 (a whole day later) is valid at any time of day.
    private static void requireWithinLimits(Iso8601Fields date, IsoTimeFields time) {
        final var epochDay = LocalDate.of(date.year(), date.month(), date.day()).toEpochDay();
        final var epochNanos = BigInteger.valueOf(epochDay).multiply(NANOS_PER_DAY).add(toNanosOfDay(time));
        final var minLimit = INSTANT_EPOCH_NANOS_LIMIT.negate().subtract(NANOS_PER_DAY);
        final var maxLimit = INSTANT_EPOCH_NANOS_LIMIT.add(NANOS_PER_DAY);
        if (epochNanos.compareTo(minLimit) <= 0 || epochNanos.compareTo(maxLimit) >= 0) {
            throw new RangeErrorException(
                    "Temporal.PlainDateTime outside of representable range: " + date + " " + time);
        }
    }

    // The single construction choke point every other factory in this file routes through, so the
    // representable-range check (above) is never skippable.
    private static JsTemporalPlainDateTime dateTime(Iso8601Fields date, IsoTimeFields time) {
        requireWithinLimits(date, time);
        return new JsTemporalPlainDateTime(date, time);
    }

    // Constructor / withCalendar accept only a bare calendar identifier; a non-string value is a
    // TypeError, not a RangeError.
    private static String requireCalendarString(JsValue calendarArg) {
        if (!(calendarArg instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        return TemporalCalendarIdentifier.canonicalizeBare(s.getValue());
    }

    public static void installAccessors(JsObject proto) {
        installGetter(proto, "year", r -> new JsNumber(requireReceiver(r, "year").year()));
        installGetter(proto, "month", r -> new JsNumber(requireReceiver(r, "month").month()));
        installGetter(proto, "monthCode", r -> new JsString(monthCode(requireReceiver(r, "monthCode").month())));
        installGetter(proto, "day", r -> new JsNumber(requireReceiver(r, "day").day()));
        installGetter(proto, "hour", r -> new JsNumber(requireReceiver(r, "hour").time().hour()));
        installGetter(proto, "minute", r -> new JsNumber(requireReceiver(r, "minute").time().minute()));
        installGetter(proto, "second", r -> new JsNumber(requireReceiver(r, "second").time().second()));
        installGetter(proto, "millisecond", r -> new JsNumber(requireReceiver(r, "millisecond").time().millisecond()));
        installGetter(proto, "microsecond", r -> new JsNumber(requireReceiver(r, "microsecond").time().microsecond()));
        installGetter(proto, "nanosecond", r -> new JsNumber(requireReceiver(r, "nanosecond").time().nanosecond()));
        installGetter(proto, "dayOfWeek",
                r -> new JsNumber(IsoCalendar.dayOfWeek(requireReceiver(r, "dayOfWeek").date())));
        installGetter(proto, "dayOfYear",
                r -> new JsNumber(IsoCalendar.dayOfYear(requireReceiver(r, "dayOfYear").date())));
        installGetter(proto, "weekOfYear",
                r -> new JsNumber(IsoCalendar.weekOfYear(requireReceiver(r, "weekOfYear").date())));
        installGetter(proto, "yearOfWeek",
                r -> new JsNumber(IsoCalendar.yearOfWeek(requireReceiver(r, "yearOfWeek").date())));
        installGetter(proto, "daysInWeek", r -> {
            requireReceiver(r, "daysInWeek");
            return new JsNumber(7);
        });
        installGetter(proto, "daysInMonth", r -> {
            final var d = requireReceiver(r, "daysInMonth");
            return new JsNumber(IsoCalendar.daysInMonth(d.year(), d.month()));
        });
        installGetter(proto, "daysInYear", r -> {
            final var d = requireReceiver(r, "daysInYear");
            return new JsNumber(IsoCalendar.daysInYear(d.year()));
        });
        installGetter(proto, "monthsInYear", r -> {
            requireReceiver(r, "monthsInYear");
            return new JsNumber(12);
        });
        installGetter(proto, "inLeapYear", r -> {
            final var d = requireReceiver(r, "inLeapYear");
            return JsBoolean.of(IsoCalendar.isLeapYear(d.year()));
        });
        installGetter(proto, "calendarId", r -> {
            requireReceiver(r, "calendarId");
            return new JsString("iso8601");
        });
        installGetter(proto, "era", r -> {
            requireReceiver(r, "era");
            return JsUndefined.getInstance();
        });
        installGetter(proto, "eraYear", r -> {
            requireReceiver(r, "eraYear");
            return JsUndefined.getInstance();
        });
    }

    private static void installGetter(JsObject proto, String name, Function<JsValue, JsValue> impl) {
        final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> impl.apply(thisArg));
        getter.setLength(0);
        proto.defineAccessor(name, getter, null);
        proto.setFlags(name, new JsObject.PropertyFlags(true, false, true));
    }

    private static JsTemporalPlainDateTime requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalPlainDateTime dt) {
            return dt;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainDateTime wrapped) {
            return wrapped;
        }
        throw new TypeErrorException(
                "Temporal.PlainDateTime.prototype." + method + " called on an incompatible receiver");
    }

    public static JsValue getMethod(JsTemporalPlainDateTime receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
            case "withCalendar" ->
                new JsNativeFunction("withCalendar", (_, args) -> withCalendar(receiver, arg(args, 0), ops));
            case "withPlainTime" ->
                new JsNativeFunction("withPlainTime", (_, args) -> withPlainTime(receiver, arg(args, 0), ops));
            case "add" -> new JsNativeFunction("add", (_, args) -> add(receiver, arg(args, 0), arg(args, 1), ops));
            case "subtract" ->
                new JsNativeFunction("subtract", (_, args) -> subtract(receiver, arg(args, 0), arg(args, 1), ops));
            case "until" ->
                new JsNativeFunction("until", (_, args) -> until(receiver, arg(args, 0), arg(args, 1), ops));
            case "since" ->
                new JsNativeFunction("since", (_, args) -> since(receiver, arg(args, 0), arg(args, 1), ops));
            case "round" -> new JsNativeFunction("round", (_, args) -> round(receiver, arg(args, 0), ops));
            case "equals" -> new JsNativeFunction("equals", (_, args) -> equalsMethod(receiver, arg(args, 0), ops));
            case "toPlainDate" -> new JsNativeFunction("toPlainDate", (_, _) -> toPlainDate(receiver));
            case "toPlainTime" -> new JsNativeFunction("toPlainTime", (_, _) -> toPlainTime(receiver));
            case "toPlainYearMonth" -> new JsNativeFunction("toPlainYearMonth", (_, _) -> toPlainYearMonth(receiver));
            case "toPlainMonthDay" -> new JsNativeFunction("toPlainMonthDay", (_, _) -> toPlainMonthDay(receiver));
            case "toZonedDateTime" -> new JsNativeFunction("toZonedDateTime",
                    (_, args) -> toZonedDateTime(receiver, arg(args, 0), arg(args, 1), ops));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "getISOFields" -> new JsNativeFunction("getISOFields", (_, _) -> getISOFields(receiver));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException(
                        "Cannot convert a Temporal.PlainDateTime to a primitive value with valueOf; use compare() or "
                                + "equals() instead");
            });
            default -> null;
        };
    }

    // ToTemporalDateTime: accepts a real PlainDateTime (copied), an ISO date-time string, or a
    // date-time-like object (date fields required, time fields default to 0, regulated per overflow).
    private static JsTemporalPlainDateTime toDateTime(JsValue item, InterpreterOps ops) {
        return toDateTime(item, JsUndefined.getInstance(), ops);
    }

    // Item-type dispatch happens BEFORE any options are touched: a Temporal instance/PlainDate/
    // ZonedDateTime/string is recognized (or a string is parsed, which can throw) first, and only then
    // is the (still fully validated, even though its result is discarded) overflow option read - this
    // matches from/order-of-operations.js requiring "get options.overflow" even when cloning an
    // instance or parsing a string, and from/observable-get-overflow-argument-string-invalid.js
    // requiring options to be left untouched when the string itself fails to parse.
    private static JsTemporalPlainDateTime toDateTime(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        if (item instanceof JsTemporalPlainDateTime dt) {
            readOverflowOption(optionsArg, ops);
            return dateTime(dt.date(), dt.time());
        }
        // ToTemporalDateTime's fast paths for other Temporal types carrying an ISO date: a PlainDate's
        // date fields are taken directly with the time defaulted to midnight, and a ZonedDateTime's
        // date+time fields are taken via the zone's local wall-clock reading - both bypass the generic
        // property-bag path entirely (no Get calls on the argument).
        if (item instanceof JsTemporalPlainDate pd) {
            readOverflowOption(optionsArg, ops);
            return dateTime(pd.fields(), new IsoTimeFields(0, 0, 0, 0, 0, 0));
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            readOverflowOption(optionsArg, ops);
            final var isoFields = zdt.isoFieldsAtLocal();
            return dateTime(isoFields.date(), isoFields.time());
        }
        if (item instanceof JsString s) {
            // A bare date (no time part) is accepted, defaulting the time to midnight - PlainDateTime
            // accepts a superset of PlainDate's own string grammar plus an optional time part.
            final var parsed = TemporalParser.parseDate(s.getValue());
            if (parsed.calendar() != null) {
                TemporalCalendarIdentifier.canonicalizeBare(parsed.calendar());
            }
            readOverflowOption(optionsArg, ops);
            final var time = parsed.time() != null ? parsed.time() : new IsoTimeFields(0, 0, 0, 0, 0, 0);
            return dateTime(parsed.date(), time);
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return dateTimeFromFields(item, optionsArg, ops);
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.PlainDateTime");
    }

    // PrepareCalendarFields reads every recognized field in alphabetical order (calendar first, then
    // day/hour/microsecond/millisecond/minute/month/monthCode/nanosecond/second/year) regardless of
    // presence, then GetTemporalOverflowOption runs last - see from/order-of-operations.js. day/month
    // use ToPositiveIntegerWithTruncation (reject <= 0 unconditionally, independent of overflow - see
    // from/negative-month-or-day.js); monthCode's *syntax* is validated the moment it is read (RangeError
    // immediately, before any later field is even read - see from/monthcode-invalid.js's "L99M" case)
    // while its *numeric suitability* (range/leap-suffix) and any month/monthCode conflict are resolved
    // only after every field (including year) has been read - see
    // from/calendarresolvefields-error-ordering.js and from/monthcode-invalid.js's "M99L" case.
    private static JsTemporalPlainDateTime dateTimeFromFields(JsValue obj, JsValue optionsArg, InterpreterOps ops) {
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
        final var second = fieldOrDefault(obj, "second", 0, ops);
        final var yearValue = ops.getMember(obj, new JsString("year"));
        if (yearValue instanceof JsUndefined) {
            throw new TypeErrorException("year is required");
        }
        final var year = toIntegerField(yearValue, "year", ops);
        final var resolvedMonth = resolveMonthValue(month, monthCode);
        final var overflow = readOverflowOption(optionsArg, ops);
        final var date = IsoCalendar.regulateDate(year, resolvedMonth, day, overflow);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return dateTime(date, time);
    }

    // ToPositiveIntegerWithTruncation: unlike ToIntegerWithTruncation, a non-positive result is always
    // a RangeError, regardless of the overflow option (which only constrains an in-range-but-excessive
    // value, e.g. day 30 in February - it never accepts a negative one).
    private static int toPositiveIntegerField(JsValue value, String name, InterpreterOps ops) {
        final var result = toIntegerField(value, name, ops);
        if (result < 1) {
            throw new RangeErrorException(name + " must be a positive integer, got " + result);
        }
        return result;
    }

    // ToMonthCode: value must be (or ToPrimitive-convert to, with the "string" hint) an actual String -
    // a non-string primitive (number, bigint, boolean, symbol, null) or an object whose ToPrimitive
    // result isn't a string is rejected with TypeError before any syntax check ever runs.
    private static String requireMonthCodeSyntax(JsValue value, InterpreterOps ops) {
        final var primitive = JsCoercion.toPrimitive(value, "string", ops);
        if (!(primitive instanceof JsString s)) {
            throw new TypeErrorException("monthCode must be a string");
        }
        final var code = s.getValue();
        if (!isSyntacticallyValidMonthCode(code)) {
            throw new RangeErrorException("Invalid monthCode: " + code);
        }
        return code;
    }

    // MMonthCode ::: "M" DecimalDigit DecimalDigit "L"? - the syntactic shape only; whether the number
    // is 1..12 (and ISO never allows the leap-month "L" suffix) is a separate, later check.
    private static boolean isSyntacticallyValidMonthCode(String code) {
        final var length = code.length();
        if (length != 3 && length != 4) {
            return false;
        }
        return code.charAt(0) == 'M' && Character.isDigit(code.charAt(1)) && Character.isDigit(code.charAt(2))
                && (length == 3 || code.charAt(3) == 'L');
    }

    // The numeric/semantic half of monthCode resolution: value 1..12, no leap-month suffix (ISO 8601
    // has no leap months), and must agree with an explicit numeric `month` field if both are present.
    private static int monthCodeNumericValue(String code) {
        if (code.length() == 4) {
            throw new RangeErrorException("Invalid monthCode for the iso8601 calendar: " + code);
        }
        final var value = Integer.parseInt(code.substring(1));
        if (value < 1 || value > 12) {
            throw new RangeErrorException("Invalid monthCode for the iso8601 calendar: " + code);
        }
        return value;
    }

    private static int resolveMonthValue(Integer month, String monthCode) {
        if (monthCode == null) {
            return month;
        }
        final var resolved = monthCodeNumericValue(monthCode);
        if (month != null && month != resolved) {
            throw new RangeErrorException("month and monthCode are inconsistent");
        }
        return resolved;
    }

    private static int resolveMonthValue(Integer month, String monthCode, int defaultMonth) {
        if (month == null && monthCode == null) {
            return defaultMonth;
        }
        return resolveMonthValue(month, monthCode);
    }

    private static int fieldOrDefault(JsValue obj, String name, int defaultValue, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        return value instanceof JsUndefined ? defaultValue : toIntegerField(value, name, ops);
    }

    private static String monthCode(int month) {
        return "M" + pad2(month);
    }

    private static String pad2(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
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

    private static RegulateOverflow readOverflowOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "overflow", ops);
        return value instanceof JsUndefined
                ? RegulateOverflow.CONSTRAIN
                : RegulateOverflow.parse(JsCoercion.toStr(value, ops));
    }

    private static Unit readSmallestUnitOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "smallestUnit", ops);
        return value instanceof JsUndefined ? Unit.NANOSECOND : Unit.parseTemporalUnit(JsCoercion.toStr(value, ops));
    }

    private static TemporalFormatter.CalendarName readCalendarNameOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "calendarName", ops);
        return value instanceof JsUndefined
                ? TemporalFormatter.CalendarName.AUTO
                : TemporalFormatter.CalendarName.parse(JsCoercion.toStr(value, ops));
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

    // round() rejects an out-of-range increment against the unit's natural cycle length (e.g. 24 for
    // hour); "day" only ever accepts an increment of 1, since round() rounds a single wall-clock day
    // as a whole rather than a multi-day cycle.
    private static void validateRoundingIncrement(long increment, Unit unit) {
        if (unit == Unit.DAY) {
            if (increment != 1) {
                throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit day");
            }
            return;
        }
        validateRoundingIncrementForDuration(increment, unit);
    }

    // until()/since() round a computed Duration rather than a wall-clock reading, so a calendar unit
    // (day here - year/month/week never reach this path since they always take the
    // RelativeDurationMath branch) has no natural cycle length to divide evenly into and any
    // increment in [1, 1e9] is valid; time units keep the same bounded-divisor rule as round().
    private static void validateRoundingIncrementForDuration(long increment, Unit unit) {
        if (unit == Unit.DAY) {
            return;
        }
        final var maximum = switch (unit) {
            case HOUR -> 24;
            case MINUTE, SECOND -> 60;
            case MILLISECOND, MICROSECOND, NANOSECOND -> 1000;
            default -> throw new RangeErrorException("Invalid unit for rounding: " + unit);
        };
        if (increment < 1 || maximum % increment != 0 || increment == maximum) {
            throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit " + unit.singular());
        }
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

    // A null-prototype object (OrdinaryObjectCreate(null)) so a lookup of an absent option key never
    // falls through to Object.prototype - see round/string-shorthand-no-object-prototype-pollution.js.
    private static JsObject smallestUnitOptions(JsString value) {
        final var options = new JsObject();
        options.setProto(null);
        options.set("smallestUnit", value);
        return options;
    }

    // RejectObjectWithCalendarOrTimeZone runs first (calendar/timeZone properties are disallowed on a
    // with() argument), then every field is read in alphabetical order regardless of presence (day
    // and month use ToPositiveIntegerWithTruncation exactly like from(), so with({day: -1}, badOptions)
    // still RangeErrors before the options argument's own type is ever checked - see
    // with/options-wrong-type.js), then GetTemporalOverflowOption runs last - see
    // with/order-of-operations.js.
    private static JsValue with(JsTemporalPlainDateTime receiver, JsValue fieldsLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (isAnyTemporalValue(fieldsLike) || !InterpreterUtils.isObjectLike(fieldsLike)) {
            throw new TypeErrorException("Temporal.PlainDateTime.prototype.with argument must be a plain object");
        }
        rejectCalendarOrTimeZoneField(fieldsLike, ops);
        final var t = receiver.time();
        var any = false;
        final var dayValue = ops.getMember(fieldsLike, new JsString("day"));
        any |= !(dayValue instanceof JsUndefined);
        final var day = dayValue instanceof JsUndefined ? receiver.day() : toPositiveIntegerField(dayValue, "day", ops);
        final var hourValue = ops.getMember(fieldsLike, new JsString("hour"));
        any |= !(hourValue instanceof JsUndefined);
        final var hour = hourValue instanceof JsUndefined ? t.hour() : toIntegerField(hourValue, "hour", ops);
        final var microsecondValue = ops.getMember(fieldsLike, new JsString("microsecond"));
        any |= !(microsecondValue instanceof JsUndefined);
        final var microsecond = microsecondValue instanceof JsUndefined
                ? t.microsecond()
                : toIntegerField(microsecondValue, "microsecond", ops);
        final var millisecondValue = ops.getMember(fieldsLike, new JsString("millisecond"));
        any |= !(millisecondValue instanceof JsUndefined);
        final var millisecond = millisecondValue instanceof JsUndefined
                ? t.millisecond()
                : toIntegerField(millisecondValue, "millisecond", ops);
        final var minuteValue = ops.getMember(fieldsLike, new JsString("minute"));
        any |= !(minuteValue instanceof JsUndefined);
        final var minute = minuteValue instanceof JsUndefined ? t.minute() : toIntegerField(minuteValue, "minute", ops);
        final var monthValue = ops.getMember(fieldsLike, new JsString("month"));
        any |= !(monthValue instanceof JsUndefined);
        final Integer month = monthValue instanceof JsUndefined
                ? null
                : toPositiveIntegerField(monthValue, "month", ops);
        final var monthCodeValue = ops.getMember(fieldsLike, new JsString("monthCode"));
        any |= !(monthCodeValue instanceof JsUndefined);
        final var monthCode = monthCodeValue instanceof JsUndefined
                ? null
                : requireMonthCodeSyntax(monthCodeValue, ops);
        final var nanosecondValue = ops.getMember(fieldsLike, new JsString("nanosecond"));
        any |= !(nanosecondValue instanceof JsUndefined);
        final var nanosecond = nanosecondValue instanceof JsUndefined
                ? t.nanosecond()
                : toIntegerField(nanosecondValue, "nanosecond", ops);
        final var secondValue = ops.getMember(fieldsLike, new JsString("second"));
        any |= !(secondValue instanceof JsUndefined);
        final var second = secondValue instanceof JsUndefined ? t.second() : toIntegerField(secondValue, "second", ops);
        final var yearValue = ops.getMember(fieldsLike, new JsString("year"));
        any |= !(yearValue instanceof JsUndefined);
        final var year = yearValue instanceof JsUndefined ? receiver.year() : toIntegerField(yearValue, "year", ops);
        if (!any) {
            throw new TypeErrorException("with() argument must contain at least one recognized property");
        }
        final var resolvedMonth = resolveMonthValue(month, monthCode, receiver.month());
        final var overflow = readOverflowOption(optionsArg, ops);
        final var date = IsoCalendar.regulateDate(year, resolvedMonth, day, overflow);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return dateTime(date, time);
    }

    private static void rejectCalendarOrTimeZoneField(JsValue fieldsLike, InterpreterOps ops) {
        if (!(ops.getMember(fieldsLike, new JsString("calendar")) instanceof JsUndefined)) {
            throw new TypeErrorException(
                    "Temporal.PlainDateTime.prototype.with argument must not have a calendar " + "property");
        }
        if (!(ops.getMember(fieldsLike, new JsString("timeZone")) instanceof JsUndefined)) {
            throw new TypeErrorException(
                    "Temporal.PlainDateTime.prototype.with argument must not have a timeZone " + "property");
        }
    }

    // The five Temporal types carrying a calendar - accepted as a withCalendar()/property-bag
    // `calendar` argument (their own calendar is read via a fast path, without ever touching a
    // "calendar"/"timeZone" property on them).
    private static boolean isTemporalWithCalendar(JsValue value) {
        return value instanceof JsTemporalPlainDate || value instanceof JsTemporalPlainDateTime
                || value instanceof JsTemporalPlainMonthDay || value instanceof JsTemporalPlainYearMonth
                || value instanceof JsTemporalZonedDateTime;
    }

    // Every built-in Temporal type is rejected outright as a with() argument (not just the
    // calendar-bearing ones - a plain Temporal.PlainTime is equally not a valid partial-fields
    // object) - the check runs before any property is read at all, so a poisoned calendar/timeZone
    // getter on the instance is never invoked - see with/calendar-temporal-object-throws.js.
    private static boolean isAnyTemporalValue(JsValue value) {
        return isTemporalWithCalendar(value) || value instanceof JsTemporalPlainTime;
    }

    // withCalendar is an identity operation in ISO-only mode - the only effect is validating the arg,
    // which (unlike the constructor's bare-identifier-only calendar argument) accepts the broader
    // CalendarString grammar (a full ISO date/date-time/time string, extracting or defaulting its u-ca
    // annotation) or any of the five Temporal types carrying an ISO date, read via a fast path that
    // never touches the argument's own calendar/timeZone properties.
    private static JsValue withCalendar(JsTemporalPlainDateTime receiver, JsValue calendarArg, InterpreterOps ops) {
        if (!isTemporalWithCalendar(calendarArg)) {
            if (!(calendarArg instanceof JsString s)) {
                throw new TypeErrorException("calendar must be a string");
            }
            TemporalCalendarIdentifier.canonicalizeFlexible(s.getValue());
        }
        return dateTime(receiver.date(), receiver.time());
    }

    // Property-bag `calendar` field: accepts a bare identifier or a full ISO string carrying (or
    // defaulting) a u-ca annotation.
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

    private static JsValue withPlainTime(JsTemporalPlainDateTime receiver, JsValue timeLike, InterpreterOps ops) {
        return dateTime(receiver.date(), toPlainTimeOrMidnight(timeLike, ops));
    }

    // ToTemporalTime, narrowed to withPlainTime's needs: an absent argument defaults to midnight
    // (replacing, not merging, unlike with()'s per-field defaults).
    private static IsoTimeFields toPlainTimeOrMidnight(JsValue timeLike, InterpreterOps ops) {
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
            case JsString s -> {
                return TemporalParser.parseTime(s.getValue()).time();
            }
            default -> {
            }
        }
        if (InterpreterUtils.isObjectLike(timeLike)) {
            return timeFromObjectRequireAny(timeLike, ops);
        }
        throw new TypeErrorException("Temporal.PlainDateTime.prototype.withPlainTime requires a time-like value");
    }

    // Fields are read in alphabetical order (hour, microsecond, millisecond, minute, nanosecond,
    // second) - see withPlainTime/order-of-operations.js.
    private static IsoTimeFields timeFromObjectRequireAny(JsValue obj, InterpreterOps ops) {
        final var hourValue = ops.getMember(obj, new JsString("hour"));
        final var hour = hourValue instanceof JsUndefined ? 0 : toIntegerField(hourValue, "hour", ops);
        final var microsecondValue = ops.getMember(obj, new JsString("microsecond"));
        final var microsecond = microsecondValue instanceof JsUndefined
                ? 0
                : toIntegerField(microsecondValue, "microsecond", ops);
        final var millisecondValue = ops.getMember(obj, new JsString("millisecond"));
        final var millisecond = millisecondValue instanceof JsUndefined
                ? 0
                : toIntegerField(millisecondValue, "millisecond", ops);
        final var minuteValue = ops.getMember(obj, new JsString("minute"));
        final var minute = minuteValue instanceof JsUndefined ? 0 : toIntegerField(minuteValue, "minute", ops);
        final var nanosecondValue = ops.getMember(obj, new JsString("nanosecond"));
        final var nanosecond = nanosecondValue instanceof JsUndefined
                ? 0
                : toIntegerField(nanosecondValue, "nanosecond", ops);
        final var secondValue = ops.getMember(obj, new JsString("second"));
        final var second = secondValue instanceof JsUndefined ? 0 : toIntegerField(secondValue, "second", ops);
        if (hourValue instanceof JsUndefined && microsecondValue instanceof JsUndefined
                && millisecondValue instanceof JsUndefined && minuteValue instanceof JsUndefined
                && nanosecondValue instanceof JsUndefined && secondValue instanceof JsUndefined) {
            throw new TypeErrorException("Invalid time-like object: no recognized properties");
        }
        return regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, RegulateOverflow.CONSTRAIN);
    }

    // ToTemporalDuration: a real Temporal.Duration (via InterpreterUtils.isObjectLike + getMember
    // duck-typing, so a genuine instance and a plain duration-like object are both accepted), an ISO
    // duration string, or a plain duration-like object.
    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsString s) {
            final var fields = TemporalParser.parseDuration(s.getValue());
            DurationMath.sign(fields);
            return fields;
        }
        if (InterpreterUtils.isObjectLike(value)) {
            // Fields are read in alphabetical order (days, hours, microseconds, milliseconds, minutes,
            // months, nanoseconds, seconds, weeks, years) - see add/order-of-operations.js.
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
        throw new TypeErrorException(
                "Expected a Temporal.Duration, an ISO 8601 duration string, or a duration-like object");
    }

    // ToIntegerIfIntegral: a Duration-like field must already be an integer (no truncation). A
    // missing property returns null (rather than defaulting to 0 here) so the caller can reject a
    // duration-like value that has none of the ten recognized properties present.
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

    private static JsValue add(JsTemporalPlainDateTime receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        return addDateTime(receiver, toDurationFields(durationLike, ops), readOverflowOption(optionsArg, ops));
    }

    private static JsValue subtract(JsTemporalPlainDateTime receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        return addDateTime(receiver, negate(toDurationFields(durationLike, ops)), readOverflowOption(optionsArg, ops));
    }

    // AddDateTime: the duration's time units are balanced against the receiver's time-of-day first
    // (any overflow/underflow carries a whole day), then the date arithmetic (years/months/weeks plus
    // the day carry) is applied via IsoCalendar.addDate - the same two-phase order addDate itself uses
    // for its own years/months-then-days split, so e.g. "Jan 31, 23:00 + 1 month, 2 hours" regulates
    // against February's real length before the day carry is added.
    private static JsValue addDateTime(JsTemporalPlainDateTime receiver, DurationFields duration,
            RegulateOverflow overflow) {
        final var nanosOfDay = toNanosOfDay(receiver.time());
        final var deltaTimeNanos = toDurationNanos(duration);
        final var total = nanosOfDay.add(deltaTimeNanos);
        final var dayCarry = total.subtract(total.mod(NANOS_PER_DAY)).divide(NANOS_PER_DAY);
        final var newNanosOfDay = total.subtract(dayCarry.multiply(NANOS_PER_DAY));
        final var newDate = IsoCalendar.addDate(receiver.date(), duration.years(), duration.months(), duration.weeks(),
                duration.days() + dayCarry.doubleValue(), overflow);
        return dateTime(newDate, fromNanosOfDay(newNanosOfDay.longValueExact()));
    }

    private static BigInteger toNanosOfDay(IsoTimeFields t) {
        return BigInteger.valueOf(t.hour()).multiply(NANOS_PER_HOUR)
                .add(BigInteger.valueOf(t.minute()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf(t.second()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf(t.millisecond()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf(t.microsecond()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf(t.nanosecond()));
    }

    private static IsoTimeFields fromNanosOfDay(long nanos) {
        var remaining = nanos;
        final var hour = (int) (remaining / NANOS_PER_HOUR_LONG);
        remaining %= NANOS_PER_HOUR_LONG;
        final var minute = (int) (remaining / NANOS_PER_MINUTE_LONG);
        remaining %= NANOS_PER_MINUTE_LONG;
        final var second = (int) (remaining / NANOS_PER_SECOND_LONG);
        remaining %= NANOS_PER_SECOND_LONG;
        final var millisecond = (int) (remaining / NANOS_PER_MILLI_LONG);
        remaining %= NANOS_PER_MILLI_LONG;
        final var microsecond = (int) (remaining / NANOS_PER_MICRO_LONG);
        final var nanosecond = (int) (remaining % NANOS_PER_MICRO_LONG);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    private static BigInteger toDurationNanos(DurationFields f) {
        return BigInteger.valueOf((long) f.hours()).multiply(NANOS_PER_HOUR)
                .add(BigInteger.valueOf((long) f.minutes()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf((long) f.seconds()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf((long) f.milliseconds()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf((long) f.microseconds()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf((long) f.nanoseconds()));
    }

    private static JsValue until(JsTemporalPlainDateTime receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, false, ops);
    }

    private static JsValue since(JsTemporalPlainDateTime receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, true, ops);
    }

    // DifferenceTemporalPlainDateTime: computed once in the "until" direction (receiver -> other),
    // mirroring TemporalPlainTimeBuiltins' since/until pairing - `since` negates the rounding mode
    // before rounding (asymmetric modes like ceil/floor need to flip direction) and negates the
    // resulting fields afterward, rather than recomputing with swapped operands.
    private static JsValue difference(JsTemporalPlainDateTime receiver, JsValue otherArg, JsValue optionsArg,
            boolean isSince, InterpreterOps ops) {
        final var other = toDateTime(otherArg, ops);
        // GetDifferenceSettings reads every option in alphabetical order (largestUnit, roundingIncrement,
        // roundingMode, smallestUnit) before any of the algorithmic validation below runs - see
        // since/order-of-operations.js. largestUnit's raw string is captured first and resolved against
        // smallestUnit only once smallestUnit itself has been read.
        final var largestUnitValue = optionOrUndefined(optionsArg, "largestUnit", ops);
        final var largestUnitRaw = largestUnitValue instanceof JsUndefined
                ? null
                : JsCoercion.toStr(largestUnitValue, ops);
        final var increment = readIncrementOption(optionsArg, ops);
        var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        final var smallestUnit = readSmallestUnitOption(optionsArg, ops);
        // largestUnit defaults to (and "auto" resolves to) whichever of smallestUnit/day is coarser,
        // so a smallestUnit larger than the usual "day" default (e.g. "years") doesn't spuriously
        // conflict with it.
        final var largestUnitDefault = smallestUnit.isLargerThan(Unit.DAY) ? smallestUnit : Unit.DAY;
        final var largestUnit = largestUnitRaw == null || "auto".equals(largestUnitRaw)
                ? largestUnitDefault
                : Unit.parseTemporalUnit(largestUnitRaw);
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        if (isSince) {
            mode = negateRoundingMode(mode);
        }
        var fields = DurationMath.differenceCalendar(receiver.date(), receiver.time(), other.date(), other.time(),
                largestUnit);
        if (smallestUnit != Unit.NANOSECOND || increment != 1) {
            if (largestUnit.isLargerThan(Unit.DAY)) {
                final var anchor = RelativeDurationMath.Anchor.plain(receiver.date(), receiver.time());
                fields = RelativeDurationMath.roundedDifference(anchor, other.date(), other.time(), largestUnit,
                        smallestUnit, increment, mode);
            } else {
                validateRoundingIncrementForDuration(increment, smallestUnit);
                fields = DurationMath.roundDuration(fields, smallestUnit, increment, mode, largestUnit);
            }
        }
        if (isSince) {
            fields = negate(fields);
        }
        return new JsTemporalDuration(fields);
    }

    private static JsValue equalsMethod(JsTemporalPlainDateTime receiver, JsValue otherArg, InterpreterOps ops) {
        return JsBoolean.of(receiver.sameValue(toDateTime(otherArg, ops)));
    }

    private static JsValue round(JsTemporalPlainDateTime receiver, JsValue roundToArg, InterpreterOps ops) {
        if (roundToArg == null || roundToArg instanceof JsUndefined) {
            throw new TypeErrorException("round() requires an options parameter");
        }
        final var options = roundToArg instanceof JsString unit ? smallestUnitOptions(unit) : roundToArg;
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a string");
        }
        // All three options are read and cast in alphabetical order (roundingIncrement, roundingMode,
        // smallestUnit) before any algorithmic validation (required-ness, unit range, increment-vs-unit
        // compatibility) runs - see round/options-read-before-algorithmic-validation.js.
        final var increment = readIncrementOption(options, ops);
        final var mode = readRoundingModeOption(options, ops, RoundingMode.HALF_EXPAND);
        final var smallestUnitValue = optionOrUndefined(options, "smallestUnit", ops);
        final var smallestUnitRaw = smallestUnitValue instanceof JsUndefined
                ? null
                : JsCoercion.toStr(smallestUnitValue, ops);
        if (smallestUnitRaw == null) {
            throw new RangeErrorException("smallestUnit is required");
        }
        final var smallestUnit = Unit.parseTemporalUnit(smallestUnitRaw);
        if (smallestUnit.isLargerThan(Unit.DAY)) {
            throw new RangeErrorException(
                    "Invalid smallestUnit for Temporal.PlainDateTime.prototype.round: " + smallestUnit.singular());
        }
        validateRoundingIncrement(increment, smallestUnit);
        return roundToUnit(receiver, smallestUnit, increment, mode);
    }

    // Rounds the nanosecond-of-day total and carries any overflow/underflow into the date - unlike
    // TemporalPlainTimeBuiltins' equivalent (which wraps modulo a single day, having no date to carry
    // into).
    private static JsTemporalPlainDateTime roundByIncrementNanos(JsTemporalPlainDateTime receiver,
            BigInteger incrementNanos, RoundingMode mode) {
        final var nanosOfDay = toNanosOfDay(receiver.time());
        final var rounded = roundNonNegative(nanosOfDay, incrementNanos, mode);
        final var dayCarry = rounded.divide(NANOS_PER_DAY);
        final var remainder = rounded.subtract(dayCarry.multiply(NANOS_PER_DAY));
        final var newDate = dayCarry.signum() == 0
                ? receiver.date()
                : IsoCalendar.addDate(receiver.date(), 0, 0, 0, dayCarry.doubleValue(), RegulateOverflow.CONSTRAIN);
        return dateTime(newDate, fromNanosOfDay(remainder.longValueExact()));
    }

    private static JsTemporalPlainDateTime roundToUnit(JsTemporalPlainDateTime receiver, Unit unit, long increment,
            RoundingMode mode) {
        final var perUnit = unit == Unit.DAY ? NANOS_PER_DAY : nanosPerUnit(unit);
        return roundByIncrementNanos(receiver, perUnit.multiply(BigInteger.valueOf(increment)), mode);
    }

    private static JsTemporalPlainDateTime roundToFractionalDigits(JsTemporalPlainDateTime receiver, int digits,
            RoundingMode mode) {
        return roundByIncrementNanos(receiver, BigInteger.TEN.pow(9 - digits), mode);
    }

    private static BigInteger nanosPerUnit(Unit unit) {
        return switch (unit) {
            case HOUR -> NANOS_PER_HOUR;
            case MINUTE -> NANOS_PER_MINUTE;
            case SECOND -> NANOS_PER_SECOND;
            case MILLISECOND -> NANOS_PER_MILLI;
            case MICROSECOND -> NANOS_PER_MICRO;
            case NANOSECOND -> BigInteger.ONE;
            default -> throw new RangeErrorException("unit has no fixed nanosecond length: " + unit);
        };
    }

    // Non-negative-only rounding (a time-of-day nanosecond count is always in [0, 86400e9)) - same
    // eight-branch rule set as TemporalPlainTimeBuiltins' equivalent, deliberately not shared since
    // that one is private and this one intentionally does not mod the result (the caller needs the
    // raw carry to know whether the date advanced).
    private static BigInteger roundNonNegative(BigInteger value, BigInteger increment, RoundingMode mode) {
        final var dm = value.divideAndRemainder(increment);
        final var quotient = dm[0];
        final var remainder = dm[1];
        if (remainder.signum() == 0) {
            return value;
        }
        final var doubled = remainder.multiply(TWO);
        final var roundedQuotient = switch (mode) {
            case TRUNC, FLOOR -> quotient;
            case CEIL, EXPAND -> quotient.add(BigInteger.ONE);
            case HALF_EXPAND, HALF_CEIL -> doubled.compareTo(increment) >= 0 ? quotient.add(BigInteger.ONE) : quotient;
            case HALF_TRUNC, HALF_FLOOR -> doubled.compareTo(increment) > 0 ? quotient.add(BigInteger.ONE) : quotient;
            case HALF_EVEN -> {
                final var cmp = doubled.compareTo(increment);
                if (cmp > 0) {
                    yield quotient.add(BigInteger.ONE);
                }
                yield cmp < 0 ? quotient : (quotient.testBit(0) ? quotient.add(BigInteger.ONE) : quotient);
            }
        };
        return roundedQuotient.multiply(increment);
    }

    private static JsValue toPlainDate(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainDate(receiver.date());
    }

    private static JsValue toPlainTime(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainTime(receiver.time());
    }

    private static JsValue toPlainYearMonth(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainYearMonth(new Iso8601Fields(receiver.year(), receiver.month(), 1));
    }

    private static JsValue toPlainMonthDay(JsTemporalPlainDateTime receiver) {
        return new JsTemporalPlainMonthDay(new Iso8601Fields(JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR,
                receiver.month(), receiver.day()));
    }

    // Temporal.ZonedDateTime (T7): resolves the receiver's local wall-clock fields to an instant per
    // the `disambiguation` option (default "compatible"), consulting the target zone's transition
    // directly - the same gap/fold resolution TemporalZonedDateTimeBuiltins itself uses - rather than
    // leaning on java.time.ZonedDateTime.of's fixed default behavior.
    private static JsValue toZonedDateTime(JsTemporalPlainDateTime receiver, JsValue timeZoneArg, JsValue optionsArg,
            InterpreterOps ops) {
        final var id = extractTimeZoneId(timeZoneArg, ops);
        // The disambiguation option is read (and cast) before the time zone identifier is resolved to
        // a real zone/offset, so its observable Get/toString still happens even when the zone itself is
        // unrepresentable - see toZonedDateTime/options-read-before-algorithmic-validation.js.
        final var disambiguation = readDisambiguationOption(optionsArg, ops);
        final var zone = TemporalZonedDateTimeBuiltins.zoneOf(id);
        final var date = receiver.date();
        final var time = receiver.time();
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final ZonedDateTime zdt;
        try {
            final var local = LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(),
                    time.second(), nanoOfSecond);
            zdt = resolveLocalToZoned(local, zone, disambiguation);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid Temporal.PlainDateTime for toZonedDateTime: " + e.getMessage());
        }
        // The resolved instant must itself fall within Temporal.Instant's representable range - a
        // PlainDateTime near the edge of ITS (one-day-wider) range can resolve, through a time zone
        // offset, to an instant that overflows Instant's own +/-8.64e21ns limit.
        final var epochNanos = BigInteger.valueOf(zdt.toEpochSecond()).multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(zdt.getNano()));
        JsTemporalInstant.fromEpochNanoseconds(epochNanos);
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(zdt, id);
    }

    private enum Disambiguation {
        COMPATIBLE, EARLIER, LATER, REJECT;

        static Disambiguation parse(String value) {
            return switch (value) {
                case "compatible" -> COMPATIBLE;
                case "earlier" -> EARLIER;
                case "later" -> LATER;
                case "reject" -> REJECT;
                default -> throw new RangeErrorException("Invalid disambiguation option: " + value);
            };
        }
    }

    private static Disambiguation readDisambiguationOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "disambiguation", ops);
        return value instanceof JsUndefined
                ? Disambiguation.COMPATIBLE
                : Disambiguation.parse(JsCoercion.toStr(value, ops));
    }

    private static ZonedDateTime resolveLocalToZoned(LocalDateTime local, ZoneId zone, Disambiguation disambiguation) {
        final var transition = zone.getRules().getTransition(local);
        if (transition == null) {
            return ZonedDateTime.of(local, zone);
        }
        if (transition.isGap()) {
            // See TemporalZonedDateTimeBuiltins.resolveLocal for why the offsets are swapped relative
            // to "earlier"/"later": applying the pre-transition offset to a nonexistent local time
            // lands past the real transition and renders forward-shifted ("later"/"compatible").
            return switch (disambiguation) {
                case REJECT -> throw new RangeErrorException(
                        "Temporal.PlainDateTime.prototype.toZonedDateTime: local time falls in a time zone "
                                + "transition gap and disambiguation is 'reject'");
                case EARLIER -> local.toInstant(transition.getOffsetAfter()).atZone(zone);
                case LATER, COMPATIBLE -> local.toInstant(transition.getOffsetBefore()).atZone(zone);
            };
        }
        return switch (disambiguation) {
            case REJECT -> throw new RangeErrorException(
                    "Temporal.PlainDateTime.prototype.toZonedDateTime: local time is ambiguous (time zone "
                            + "transition fold) and disambiguation is 'reject'");
            case LATER -> local.toInstant(transition.getOffsetAfter()).atZone(zone);
            case EARLIER, COMPATIBLE -> local.toInstant(transition.getOffsetBefore()).atZone(zone);
        };
    }

    private static String extractTimeZoneId(JsValue options, InterpreterOps ops) {
        String raw = null;
        if (options instanceof JsString s) {
            raw = TemporalParser.parseTimeZoneIdentifierFlexible(s.getValue());
        } else if (options instanceof JsObject obj) {
            final var timeZone = ops.getMember(obj, new JsString("timeZone"));
            if (timeZone instanceof JsString s) {
                raw = TemporalParser.parseTimeZoneIdentifierFlexible(s.getValue());
            }
        }
        if (raw == null) {
            throw new TypeErrorException("Temporal.PlainDateTime.prototype.toZonedDateTime requires a timeZone");
        }
        // "UTC" is matched ASCII-case-insensitively (java.time's ZoneId.of is exact-case); the
        // canonical spelling is used both for zone resolution and for the stored timeZoneId text.
        return TemporalCalendarIdentifier.asciiEqualsIgnoreCase(raw, "utc") ? "UTC" : raw;
    }

    private static JsValue toStringMethod(JsTemporalPlainDateTime receiver, JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg == null || optionsArg instanceof JsUndefined) {
            return new JsString(receiver.toString());
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        // Every option is read and cast in alphabetical order (calendarName, fractionalSecondDigits,
        // roundingMode, smallestUnit) before the cross-cutting algorithmic validation (smallestUnit must
        // be a time unit) runs - see toString/options-read-before-algorithmic-validation.js.
        final var calendarName = readCalendarNameOption(optionsArg, ops);
        final var fsdValue = optionOrUndefined(optionsArg, "fractionalSecondDigits", ops);
        Integer digits = null;
        if (!(fsdValue instanceof JsUndefined)) {
            if (fsdValue instanceof JsNumber) {
                final var floored = (int) Math.floor(JsCoercion.toNumber(fsdValue, ops));
                if (floored < 0 || floored > 9) {
                    throw new RangeErrorException("fractionalSecondDigits must be 0..9 or \"auto\", got " + floored);
                }
                digits = floored;
            } else if (!"auto".equals(JsCoercion.toStr(fsdValue, ops))) {
                throw new RangeErrorException("fractionalSecondDigits must be 0..9 or \"auto\"");
            }
        }
        final var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        final var smallestUnitValue = optionOrUndefined(optionsArg, "smallestUnit", ops);
        Unit smallestUnit = null;
        if (!(smallestUnitValue instanceof JsUndefined)) {
            smallestUnit = Unit.parseTemporalUnit(JsCoercion.toStr(smallestUnitValue, ops));
        }
        if (smallestUnit != null) {
            if (smallestUnit == Unit.MINUTE) {
                final var rounded = roundToUnit(receiver, Unit.MINUTE, 1, mode);
                return new JsString(TemporalFormatter.formatDate(rounded.date()) + "T" + pad2(rounded.time().hour())
                        + ":" + pad2(rounded.time().minute())
                        + TemporalFormatter.formatCalendarAnnotation(calendarName));
            }
            requireSecondOrSmallerUnit(smallestUnit);
            final var rounded = roundToUnit(receiver, smallestUnit, 1, mode);
            return new JsString(TemporalFormatter.formatDateTime(rounded.date(), rounded.time(),
                    digitsForUnit(smallestUnit), calendarName));
        }
        if (digits != null) {
            final var rounded = roundToFractionalDigits(receiver, digits, mode);
            return new JsString(TemporalFormatter.formatDateTime(rounded.date(), rounded.time(), digits, calendarName));
        }
        return new JsString(TemporalFormatter.formatDateTime(receiver.date(), receiver.time(), null, calendarName));
    }

    private static void requireSecondOrSmallerUnit(Unit unit) {
        if (unit.isLargerThan(Unit.SECOND)) {
            throw new RangeErrorException(
                    "smallestUnit must be minute, second, millisecond, microsecond, or nanosecond");
        }
    }

    private static int digitsForUnit(Unit unit) {
        return switch (unit) {
            case SECOND -> 0;
            case MILLISECOND -> 3;
            case MICROSECOND -> 6;
            case NANOSECOND -> 9;
            default -> throw new RangeErrorException(
                    "smallestUnit must be minute, second, millisecond, microsecond, or nanosecond");
        };
    }

    private static JsValue getISOFields(JsTemporalPlainDateTime receiver) {
        final var obj = new JsObject();
        obj.set("calendar", new JsString("iso8601"));
        obj.set("isoDay", new JsNumber(receiver.day()));
        obj.set("isoMonth", new JsNumber(receiver.month()));
        obj.set("isoYear", new JsNumber(receiver.year()));
        final var t = receiver.time();
        obj.set("isoHour", new JsNumber(t.hour()));
        obj.set("isoMinute", new JsNumber(t.minute()));
        obj.set("isoSecond", new JsNumber(t.second()));
        obj.set("isoMillisecond", new JsNumber(t.millisecond()));
        obj.set("isoMicrosecond", new JsNumber(t.microsecond()));
        obj.set("isoNanosecond", new JsNumber(t.nanosecond()));
        return obj;
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}

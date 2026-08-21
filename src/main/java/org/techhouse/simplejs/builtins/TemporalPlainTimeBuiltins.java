package org.techhouse.simplejs.builtins;

import java.math.BigInteger;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.IsoCalendar;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
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
 * {@code Temporal.PlainTime} constructor + prototype methods, shaped like {@code DateBuiltins}:
 * {@link #create(InterpreterOps)} builds the constructor/statics, {@link #getMethod} dispatches
 * prototype methods, {@link #fieldAccessor} backs the real per-instance accessor properties
 * ({@code hour}, {@code minute}, ...) that {@code Intrinsics} installs separately from
 * {@link #NAMES} (accessors are not method-shaped, mirroring RegExp's {@code PROTO_ACCESSORS}).
 *
 * <p>{@code Temporal.Duration} has not landed yet in this phase, so {@code add}/{@code subtract}/
 * {@code until}/{@code since} duck-type any object exposing numeric {@code hours}/{@code minutes}/
 * .../{@code nanoseconds} properties rather than requiring a real {@code JsTemporalDuration}, and
 * return a plain duck-typed duration-like object. {@code toPlainDateTime} accepts a duck-typed
 * {@code {year, month, day}} object (no {@code Temporal.PlainDate} type exists yet either) and
 * {@code toZonedDateTime} throws: it requires the not-yet-implemented {@code Temporal.Instant}/
 * {@code Temporal.ZonedDateTime} (timezone offset resolution, disambiguation).
 */
public final class TemporalPlainTimeBuiltins {
    public static final List<String> NAMES = List.of("with", "add", "subtract", "until", "since", "round", "equals",
            "toString", "toJSON", "toLocaleString", "toPlainDateTime", "toZonedDateTime", "getISOFields", "valueOf");
    public static final List<String> FIELD_ACCESSORS = List.of("hour", "minute", "second", "millisecond", "microsecond",
            "nanosecond");

    private static final BigInteger NANOS_PER_DAY = BigInteger.valueOf(86_400_000_000_000L);
    private static final BigInteger NANOS_PER_HOUR = BigInteger.valueOf(3_600_000_000_000L);
    private static final BigInteger NANOS_PER_MINUTE = BigInteger.valueOf(60_000_000_000L);
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger NANOS_PER_MILLI = BigInteger.valueOf(1_000_000L);
    private static final BigInteger NANOS_PER_MICRO = BigInteger.valueOf(1_000L);
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final String[] FIELD_NAMES = {"hour", "minute", "second", "millisecond", "microsecond",
            "nanosecond"};
    // PrepareTemporalFields/ToTemporalTimeRecord reads recognized properties in alphabetical order
    // (hour, microsecond, millisecond, minute, nanosecond, second) - these are indices into
    // FIELD_NAMES/the canonical hour..nanosecond value array, in that read order.
    private static final int[] ALPHA_FIELD_ORDER = {0, 4, 3, 1, 5, 2};
    private static final double DURATION_DATE_LIMIT = 4_294_967_296.0;
    private static final BigInteger DURATION_TIME_LIMIT = BigInteger.valueOf(9_007_199_254_740_992L)
            .multiply(BigInteger.valueOf(1_000_000_000L));

    private TemporalPlainTimeBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("PlainTime", (_, args) -> {
            requireNewTarget("Temporal.PlainTime");
            return withNewTargetPrototype(new JsTemporalPlainTime(constructFields(args, ops)), ops);
        });
        final var from = new JsNativeFunction("from", (_, args) -> from(args, ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare", (_, args) -> compareStatic(args, ops));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        return ctor;
    }

    // Unlike Map/Set/Date (always reached as a bare global identifier, so a plain call's thisArg is
    // reliably undefined), Temporal.PlainTime is always reached through the Temporal.PlainTime member
    // expression, so even an illegitimate plain call's thisArg is the Temporal namespace object, not
    // undefined - thisArg can't be used to allow a subclass's super() call through here. newTarget
    // alone is checked instead: a genuine `new`/Reflect.construct call always carries one.
    private static void requireNewTarget(String name) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (newTarget == null || newTarget instanceof JsUndefined) {
            throw new TypeErrorException("Constructor " + name + " requires 'new'");
        }
    }

    // OrdinaryCreateFromConstructor: Reflect.construct(Temporal.PlainTime, args, Ctor) links the new
    // instance's [[Prototype]] to Ctor.prototype rather than always to the intrinsic prototype
    // (mirrors DateBuiltins.withNewTargetPrototype).
    private static JsValue withNewTargetPrototype(JsTemporalPlainTime constructed, InterpreterOps ops) {
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

    // The constructor never constrains: every field must already be in range, unlike `with`/`from`
    // which accept an overflow option.
    private static IsoTimeFields constructFields(List<JsValue> args, InterpreterOps ops) {
        final var values = new int[6];
        for (var i = 0; i < FIELD_NAMES.length; i++) {
            values[i] = intArg(args, i, ops);
        }
        return regulateTime(values[0], values[1], values[2], values[3], values[4], values[5], RegulateOverflow.REJECT);
    }

    private static int intArg(List<JsValue> args, int index, InterpreterOps ops) {
        if (index >= args.size() || args.get(index) instanceof JsUndefined) {
            return 0;
        }
        return (int) toIntegerWithTruncation(args.get(index), ops);
    }

    private static double toIntegerWithTruncation(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new RangeErrorException("Invalid Temporal.PlainTime field: must be a finite number");
        }
        return number < 0 ? Math.ceil(number) : Math.floor(number);
    }

    private static IsoTimeFields regulateTime(int hour, int minute, int second, int millisecond, int microsecond,
            int nanosecond, RegulateOverflow overflow) {
        if (overflow == RegulateOverflow.CONSTRAIN) {
            return new IsoTimeFields(Math.clamp(hour, 0, 23), Math.clamp(minute, 0, 59), Math.clamp(second, 0, 59),
                    Math.clamp(millisecond, 0, 999), Math.clamp(microsecond, 0, 999), Math.clamp(nanosecond, 0, 999));
        }
        validateRange("hour", hour, 0, 23);
        validateRange("minute", minute, 0, 59);
        validateRange("second", second, 0, 59);
        validateRange("millisecond", millisecond, 0, 999);
        validateRange("microsecond", microsecond, 0, 999);
        validateRange("nanosecond", nanosecond, 0, 999);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    private static void validateRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new RangeErrorException(name + " must be in the range " + min + ".." + max + ", got " + value);
        }
    }

    public static JsValue fieldAccessor(JsTemporalPlainTime receiver, String name) {
        final var fields = receiver.getFields();
        return switch (name) {
            case "hour" -> new JsNumber(fields.hour());
            case "minute" -> new JsNumber(fields.minute());
            case "second" -> new JsNumber(fields.second());
            case "millisecond" -> new JsNumber(fields.millisecond());
            case "microsecond" -> new JsNumber(fields.microsecond());
            case "nanosecond" -> new JsNumber(fields.nanosecond());
            default -> null;
        };
    }

    public static JsValue getMethod(JsTemporalPlainTime receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
            case "add" -> new JsNativeFunction("add", (_, args) -> addOrSubtract(receiver, arg(args, 0), 1, ops));
            case "subtract" ->
                new JsNativeFunction("subtract", (_, args) -> addOrSubtract(receiver, arg(args, 0), -1, ops));
            case "until" -> new JsNativeFunction("until",
                    (_, args) -> difference(receiver, arg(args, 0), arg(args, 1), false, ops));
            case "since" ->
                new JsNativeFunction("since", (_, args) -> difference(receiver, arg(args, 0), arg(args, 1), true, ops));
            case "round" -> new JsNativeFunction("round", (_, args) -> round(receiver, arg(args, 0), ops));
            case "equals" -> new JsNativeFunction("equals", (_, args) -> equalsMethod(receiver, arg(args, 0), ops));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "toPlainDateTime" ->
                new JsNativeFunction("toPlainDateTime", (_, args) -> toPlainDateTime(receiver, arg(args, 0), ops));
            case "toZonedDateTime" -> new JsNativeFunction("toZonedDateTime", (_, _) -> {
                throw new TypeErrorException("Temporal.PlainTime.prototype.toZonedDateTime is not yet supported: "
                        + "Temporal.Instant/Temporal.ZonedDateTime land in a later phase");
            });
            case "getISOFields" -> new JsNativeFunction("getISOFields", (_, _) -> getIsoFields(receiver));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException("Cannot convert a Temporal.PlainTime to a primitive value");
            });
            default -> null;
        };
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }

    private static JsValue member(JsValue target, String name, InterpreterOps ops) {
        if (ops == null) {
            return target instanceof JsObject object ? object.get(name) : JsUndefined.getInstance();
        }
        return ops.getMember(target, new JsString(name));
    }

    // ToTemporalTime: a Temporal.PlainTime is copied (Temporal.from is not identity-preserving), a
    // string is parsed, and a plain object's recognized fields are read and regulated.
    private static JsTemporalPlainTime toTemporalTime(JsValue item, InterpreterOps ops) {
        return toTemporalTime(item, RegulateOverflow.CONSTRAIN, ops);
    }

    private static JsTemporalPlainTime toTemporalTime(JsValue item, RegulateOverflow overflow, InterpreterOps ops) {
        if (item instanceof JsTemporalPlainTime time) {
            return new JsTemporalPlainTime(time.getFields());
        }
        // ToTemporalTime's fast paths for other Temporal types carrying an ISO time: a PlainDateTime's
        // time fields are taken directly, and a ZonedDateTime's via the zone's local wall-clock
        // reading - both bypass the generic property-bag path entirely (no Get calls on the argument).
        if (item instanceof JsTemporalPlainDateTime dt) {
            return new JsTemporalPlainTime(dt.time());
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            return new JsTemporalPlainTime(zdt.isoFieldsAtLocal().time());
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return fromTimeLikeObject(item, overflow, ops);
        }
        if (!(item instanceof JsString str)) {
            throw new TypeErrorException("Cannot convert to Temporal.PlainTime: expected a string or a property bag");
        }
        requireUnambiguousBareTimeString(str.getValue());
        return new JsTemporalPlainTime(TemporalParser.parseTime(str.getValue()).time());
    }

    // TimeSpecWithOptionalOffsetNotAmbiguous: without a leading 'T' designator, a bare time string
    // must not also be syntactically valid as a reduced YearMonth (YYYY-MM/YYYYMM) or MonthDay
    // (MM-DD/MMDD) string - the ISO 8601 profile requires a 'T' prefix to disambiguate in that case
    // (a leading space is never accepted as a substitute, and simply fails to parse as a time on its
    // own, so no explicit check for it is needed here).
    private static void requireUnambiguousBareTimeString(String input) {
        if (input.isEmpty() || input.charAt(0) == 'T' || input.charAt(0) == 't') {
            return;
        }
        var core = input;
        final var bracket = core.indexOf('[');
        if (bracket >= 0) {
            core = core.substring(0, bracket);
        }
        if (isAmbiguousWithDateForm(core)) {
            throw new RangeErrorException("'" + input
                    + "' is ambiguous and requires a 'T' prefix to be parsed as a Temporal.PlainTime " + "string");
        }
    }

    private static boolean isAmbiguousWithDateForm(String core) {
        if (core.matches("\\d{4}-\\d{2}")) {
            return isValidMonth(core.substring(5, 7));
        }
        if (core.matches("\\d{6}")) {
            return isValidMonth(core.substring(4, 6));
        }
        if (core.matches("\\d{2}-\\d{2}")) {
            return isValidReducedMonthDay(core.substring(0, 2), core.substring(3, 5));
        }
        if (core.matches("\\d{4}")) {
            return isValidReducedMonthDay(core.substring(0, 2), core.substring(2, 4));
        }
        return false;
    }

    private static boolean isValidMonth(String digits) {
        final var month = Integer.parseInt(digits);
        return month >= 1 && month <= 12;
    }

    private static boolean isValidReducedMonthDay(String monthDigits, String dayDigits) {
        try {
            IsoCalendar.regulateDate(1972, Integer.parseInt(monthDigits), Integer.parseInt(dayDigits),
                    RegulateOverflow.REJECT);
            return true;
        } catch (RangeErrorException e) {
            return false;
        }
    }

    private static JsTemporalPlainTime fromTimeLikeObject(JsValue item, RegulateOverflow overflow, InterpreterOps ops) {
        final var values = readAlphabeticalTimeFields(item, ops);
        return new JsTemporalPlainTime(
                regulateTime(values[0], values[1], values[2], values[3], values[4], values[5], overflow));
    }

    private static int[] readAlphabeticalTimeFields(JsValue item, InterpreterOps ops) {
        final var values = new int[6];
        var any = false;
        for (final var idx : ALPHA_FIELD_ORDER) {
            final var value = member(item, FIELD_NAMES[idx], ops);
            if (!(value instanceof JsUndefined)) {
                any = true;
                values[idx] = (int) toIntegerWithTruncation(value, ops);
            }
        }
        if (!any) {
            throw new TypeErrorException("Invalid Temporal.PlainTime-like object: no recognized properties");
        }
        return values;
    }

    private static boolean isTemporalInstance(JsValue value) {
        return value instanceof JsTemporalPlainTime || value instanceof JsTemporalPlainDate
                || value instanceof JsTemporalPlainDateTime || value instanceof JsTemporalPlainMonthDay
                || value instanceof JsTemporalPlainYearMonth || value instanceof JsTemporalZonedDateTime
                || value instanceof JsTemporalInstant || value instanceof JsTemporalDuration;
    }

    // ToTemporalTime, `from()`'s own shape: unlike compare/equals/since/until (which always use
    // CONSTRAIN and have no separate options argument), `from()` must read the entire item argument
    // (fields, in alphabetical order, or the ISO string) to completion - including any TypeError/
    // RangeError that surfaces while doing so - before it reads/validates the overflow option at all.
    private static JsValue from(List<JsValue> args, InterpreterOps ops) {
        final var item = arg(args, 0);
        final var optionsArg = arg(args, 1);
        if (item instanceof JsTemporalPlainTime time) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainTime(time.getFields());
        }
        if (item instanceof JsTemporalPlainDateTime dt) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainTime(dt.time());
        }
        if (item instanceof JsTemporalZonedDateTime zdt) {
            readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainTime(zdt.isoFieldsAtLocal().time());
        }
        if (InterpreterUtils.isObjectLike(item)) {
            final var values = readAlphabeticalTimeFields(item, ops);
            final var overflow = readOverflowOption(optionsArg, ops);
            return new JsTemporalPlainTime(
                    regulateTime(values[0], values[1], values[2], values[3], values[4], values[5], overflow));
        }
        if (!(item instanceof JsString str)) {
            throw new TypeErrorException("Cannot convert to Temporal.PlainTime: expected a string or a property bag");
        }
        requireUnambiguousBareTimeString(str.getValue());
        final var time = TemporalParser.parseTime(str.getValue()).time();
        readOverflowOption(optionsArg, ops);
        return new JsTemporalPlainTime(time);
    }

    private static JsValue compareStatic(List<JsValue> args, InterpreterOps ops) {
        final var one = toTemporalTime(arg(args, 0), ops);
        final var two = toTemporalTime(arg(args, 1), ops);
        return new JsNumber(JsTemporalPlainTime.compare(one, two));
    }

    private static RegulateOverflow readOverflowOption(JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg instanceof JsUndefined) {
            return RegulateOverflow.CONSTRAIN;
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        final var value = member(optionsArg, "overflow", ops);
        return value instanceof JsUndefined
                ? RegulateOverflow.CONSTRAIN
                : RegulateOverflow.parse(JsCoercion.toStr(value, ops));
    }

    // IsPartialTemporalObject: any real Temporal.* instance is rejected here per spec - only a bare
    // time-like object may be passed to `with`. RejectObjectWithCalendarOrTimeZone reads (and
    // rejects the presence of) `calendar`/`timeZone` before any recognized time field is read, and
    // GetTemporalOverflowOption is read only after every field has been read (so an invalid field
    // value surfaces as a RangeError before an invalid options argument's TypeError).
    private static JsValue with(JsTemporalPlainTime receiver, JsValue timeLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(timeLike) || isTemporalInstance(timeLike)) {
            throw new TypeErrorException("with() argument must be a plain time-like object");
        }
        if (!(member(timeLike, "calendar", ops) instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a calendar property");
        }
        if (!(member(timeLike, "timeZone", ops) instanceof JsUndefined)) {
            throw new TypeErrorException("with() argument must not have a timeZone property");
        }
        final var current = receiver.getFields();
        final var values = new int[]{current.hour(), current.minute(), current.second(), current.millisecond(),
                current.microsecond(), current.nanosecond()};
        var any = false;
        for (final var idx : ALPHA_FIELD_ORDER) {
            final var value = member(timeLike, FIELD_NAMES[idx], ops);
            if (!(value instanceof JsUndefined)) {
                any = true;
                values[idx] = (int) toIntegerWithTruncation(value, ops);
            }
        }
        if (!any) {
            throw new TypeErrorException("Invalid Temporal.PlainTime-like object: no recognized properties");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        return new JsTemporalPlainTime(
                regulateTime(values[0], values[1], values[2], values[3], values[4], values[5], overflow));
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
        final var hour = (int) (remaining / 3_600_000_000_000L);
        remaining %= 3_600_000_000_000L;
        final var minute = (int) (remaining / 60_000_000_000L);
        remaining %= 60_000_000_000L;
        final var second = (int) (remaining / 1_000_000_000L);
        remaining %= 1_000_000_000L;
        final var millisecond = (int) (remaining / 1_000_000L);
        remaining %= 1_000_000L;
        final var microsecond = (int) (remaining / 1_000L);
        final var nanosecond = (int) (remaining % 1_000L);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
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

    private static void requireTimeUnit(Unit unit) {
        if (unit.isLargerThan(Unit.HOUR)) {
            throw new RangeErrorException("unit must be hour, minute, second, millisecond, microsecond, or nanosecond");
        }
    }

    // add/subtract wrap around 24 hours per spec (PlainTime has no date, so any date-unit carry from
    // the duration argument is silently discarded, not rejected) - ToTemporalDuration accepts a real
    // Temporal.Duration, an ISO 8601 duration string, or a plain duration-like object.
    private static JsValue addOrSubtract(JsTemporalPlainTime receiver, JsValue durationLike, int sign,
            InterpreterOps ops) {
        final var duration = toDurationFields(durationLike, ops);
        final var durationFields = new DurationFields(0, 0, 0, 0, sign * duration.hours(), sign * duration.minutes(),
                sign * duration.seconds(), sign * duration.milliseconds(), sign * duration.microseconds(),
                sign * duration.nanoseconds());
        final var total = toNanosOfDay(receiver.getFields()).add(toDurationNanos(durationFields)).mod(NANOS_PER_DAY);
        return new JsTemporalPlainTime(fromNanosOfDay(total.longValueExact()));
    }

    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsString s) {
            final var parsed = TemporalParser.parseDuration(s.getValue());
            requireValidDuration(parsed);
            return parsed;
        }
        if (!InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("Invalid Temporal.Duration-like value");
        }
        // ToTemporalDurationRecord reads the ten recognized properties in alphabetical order.
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
        requireValidDuration(fields);
        return fields;
    }

    // IsValidDuration: the date units are bounded to +-(2**32 - 1) individually, and the combined
    // time units (days included, since a PlainTime's add/subtract wraps a duration's days into its
    // nanosecond total same as hours..nanoseconds) must not reach +-2**53 seconds - this is checked
    // exactly via BigInteger nanoseconds rather than double arithmetic, since some of the boundary
    // values here are themselves at the edge of double's exact-integer range.
    private static void requireValidDuration(DurationFields f) {
        for (final var component : new double[]{f.years(), f.months(), f.weeks(), f.days(), f.hours(), f.minutes(),
                f.seconds(), f.milliseconds(), f.microseconds(), f.nanoseconds()}) {
            if (Double.isNaN(component) || Double.isInfinite(component)) {
                throw new RangeErrorException("Duration component must be a finite number");
            }
        }
        if (Math.abs(f.years()) >= DURATION_DATE_LIMIT || Math.abs(f.months()) >= DURATION_DATE_LIMIT
                || Math.abs(f.weeks()) >= DURATION_DATE_LIMIT) {
            throw new RangeErrorException("Duration years/months/weeks component is out of range");
        }
        final var totalNanos = BigInteger.valueOf((long) f.days()).multiply(NANOS_PER_DAY)
                .add(BigInteger.valueOf((long) f.hours()).multiply(NANOS_PER_HOUR))
                .add(BigInteger.valueOf((long) f.minutes()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf((long) f.seconds()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf((long) f.milliseconds()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf((long) f.microseconds()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf((long) f.nanoseconds()));
        if (totalNanos.abs().compareTo(DURATION_TIME_LIMIT) >= 0) {
            throw new RangeErrorException("Duration time component is out of range");
        }
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

    private static BigInteger toDurationNanos(DurationFields f) {
        return BigInteger.valueOf((long) f.hours()).multiply(NANOS_PER_HOUR)
                .add(BigInteger.valueOf((long) f.minutes()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf((long) f.seconds()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf((long) f.milliseconds()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf((long) f.microseconds()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf((long) f.nanoseconds()));
    }

    // until/since return a duck-typed duration-like object: other-minus-this (until) or its negation
    // (since), balanced/rounded via T0's DurationMath (largestUnit/smallestUnit restricted to
    // hour..nanosecond since a time-of-day difference can never reach a full day).
    private static JsValue difference(JsTemporalPlainTime receiver, JsValue otherArg, JsValue optionsArg,
            boolean isSince, InterpreterOps ops) {
        final var other = toTemporalTime(otherArg, ops);
        final var deltaNanos = toNanosOfDay(other.getFields()).subtract(toNanosOfDay(receiver.getFields()));
        var largestUnit = Unit.HOUR;
        var smallestUnit = Unit.NANOSECOND;
        var increment = 1L;
        var mode = RoundingMode.TRUNC;
        if (!(optionsArg instanceof JsUndefined)) {
            if (!InterpreterUtils.isObjectLike(optionsArg)) {
                throw new TypeErrorException("options must be an object");
            }
            // All recognized options are read (and cast/coerced) in alphabetical order before any of
            // them is algorithmically validated.
            String largestUnitStr = null;
            final var largestUnitValue = member(optionsArg, "largestUnit", ops);
            if (!(largestUnitValue instanceof JsUndefined)) {
                largestUnitStr = JsCoercion.toStr(largestUnitValue, ops);
            }
            Long incrementRaw = null;
            final var incrementValue = member(optionsArg, "roundingIncrement", ops);
            if (!(incrementValue instanceof JsUndefined)) {
                incrementRaw = (long) toIntegerWithTruncation(incrementValue, ops);
            }
            String modeStr = null;
            final var modeValue = member(optionsArg, "roundingMode", ops);
            if (!(modeValue instanceof JsUndefined)) {
                modeStr = JsCoercion.toStr(modeValue, ops);
            }
            String smallestUnitStr = null;
            final var smallestUnitValue = member(optionsArg, "smallestUnit", ops);
            if (!(smallestUnitValue instanceof JsUndefined)) {
                smallestUnitStr = JsCoercion.toStr(smallestUnitValue, ops);
            }
            if (largestUnitStr != null) {
                largestUnit = "auto".equals(largestUnitStr) ? Unit.HOUR : Unit.parseTemporalUnit(largestUnitStr);
                requireTimeUnit(largestUnit);
            }
            if (smallestUnitStr != null) {
                smallestUnit = Unit.parseTemporalUnit(smallestUnitStr);
                requireTimeUnit(smallestUnit);
            }
            if (incrementRaw != null) {
                if (incrementRaw < 1 || incrementRaw > 1_000_000_000) {
                    throw new RangeErrorException("roundingIncrement out of range: " + incrementRaw);
                }
                increment = incrementRaw;
            }
            validateRoundingIncrement(increment, smallestUnit);
            mode = modeStr == null ? RoundingMode.TRUNC : RoundingMode.parse(modeStr);
            if (isSince) {
                mode = negateRoundingMode(mode);
            }
        }
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        var fields = new DurationFields(0, 0, 0, 0, 0, 0, 0, 0, 0, deltaNanos.doubleValue());
        fields = DurationMath.roundDuration(fields, smallestUnit, increment, mode, largestUnit);
        if (isSince) {
            fields = negate(fields);
        }
        return new JsTemporalDuration(fields);
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

    private static DurationFields negate(DurationFields f) {
        return DurationMath.negate(f);
    }

    private static void validateRoundingIncrement(long increment, Unit unit) {
        final var maximum = switch (unit) {
            case HOUR -> 24;
            case MINUTE, SECOND -> 60;
            case MILLISECOND, MICROSECOND, NANOSECOND -> 1000;
            default -> throw new RangeErrorException("Invalid unit for time rounding: " + unit);
        };
        if (increment < 1 || maximum % increment != 0 || increment == maximum) {
            throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit " + unit.singular());
        }
    }

    private static JsValue optionsObject(JsValue value) {
        if (value instanceof JsString) {
            final var obj = new JsObject();
            obj.set("smallestUnit", value);
            obj.setProto(null);
            return obj;
        }
        return value;
    }

    private static JsValue round(JsTemporalPlainTime receiver, JsValue roundToArg, InterpreterOps ops) {
        if (roundToArg instanceof JsUndefined) {
            throw new TypeErrorException("round() requires an options parameter");
        }
        final var options = optionsObject(roundToArg);
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a string");
        }
        // All recognized options are read (and cast/coerced) in alphabetical order before any of
        // them is algorithmically validated - including the smallestUnit-is-required check.
        Long incrementRaw = null;
        final var incrementValue = member(options, "roundingIncrement", ops);
        if (!(incrementValue instanceof JsUndefined)) {
            incrementRaw = (long) toIntegerWithTruncation(incrementValue, ops);
        }
        String modeStr = null;
        final var modeValue = member(options, "roundingMode", ops);
        if (!(modeValue instanceof JsUndefined)) {
            modeStr = JsCoercion.toStr(modeValue, ops);
        }
        String smallestUnitStr = null;
        final var smallestUnitValue = member(options, "smallestUnit", ops);
        if (!(smallestUnitValue instanceof JsUndefined)) {
            smallestUnitStr = JsCoercion.toStr(smallestUnitValue, ops);
        }
        if (smallestUnitStr == null) {
            throw new RangeErrorException("smallestUnit is required");
        }
        final var smallestUnit = Unit.parseTemporalUnit(smallestUnitStr);
        requireTimeUnit(smallestUnit);
        final var increment = incrementRaw == null ? 1L : incrementRaw;
        if (incrementRaw != null && (increment < 1 || increment > 1_000_000_000)) {
            throw new RangeErrorException("roundingIncrement out of range: " + increment);
        }
        validateRoundingIncrement(increment, smallestUnit);
        final var mode = modeStr == null ? RoundingMode.HALF_EXPAND : RoundingMode.parse(modeStr);
        return new JsTemporalPlainTime(roundToUnit(receiver.getFields(), smallestUnit, increment, mode));
    }

    private static IsoTimeFields roundToUnit(IsoTimeFields fields, Unit unit, long increment, RoundingMode mode) {
        final var incrementNanos = nanosPerUnit(unit).multiply(BigInteger.valueOf(increment));
        final var rounded = roundNonNegative(toNanosOfDay(fields), incrementNanos, mode).mod(NANOS_PER_DAY);
        return fromNanosOfDay(rounded.longValueExact());
    }

    private static IsoTimeFields roundToFractionalDigits(IsoTimeFields fields, int digits, RoundingMode mode) {
        final var incrementNanos = BigInteger.TEN.pow(9 - digits);
        final var rounded = roundNonNegative(toNanosOfDay(fields), incrementNanos, mode).mod(NANOS_PER_DAY);
        return fromNanosOfDay(rounded.longValueExact());
    }

    // Non-negative-only rounding (a time-of-day nanosecond count is always in [0, 86400e9)), so ties
    // "away from zero" and "toward positive infinity" coincide - simpler than DurationMath's signed
    // variant, which this deliberately does not reuse (that one is private and signed).
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

    private static JsValue equalsMethod(JsTemporalPlainTime receiver, JsValue other, InterpreterOps ops) {
        return JsBoolean.of(JsTemporalPlainTime.compare(receiver, toTemporalTime(other, ops)) == 0);
    }

    private static JsValue toStringMethod(JsTemporalPlainTime receiver, JsValue optionsArg, InterpreterOps ops) {
        if (optionsArg instanceof JsUndefined) {
            return new JsString(receiver.toString());
        }
        if (!InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        // GetStringOrNumberOption's Number-vs-String branch is chosen from the value's own type as
        // returned by Get (never re-derived after further coercion) - a real Number takes the
        // ToNumber path, everything else (including a wrapper object whose valueOf/toString return a
        // number) goes through ToString. All three options are read (and cast/coerced) in
        // alphabetical order before any of them is algorithmically validated.
        Double fsdNumeric = null;
        String fsdString = null;
        final var fsdRaw = member(optionsArg, "fractionalSecondDigits", ops);
        if (!(fsdRaw instanceof JsUndefined)) {
            if (fsdRaw instanceof JsNumber) {
                fsdNumeric = JsCoercion.toNumber(fsdRaw, ops);
            } else {
                fsdString = JsCoercion.toStr(fsdRaw, ops);
            }
        }
        String modeStr = null;
        final var modeValue = member(optionsArg, "roundingMode", ops);
        if (!(modeValue instanceof JsUndefined)) {
            modeStr = JsCoercion.toStr(modeValue, ops);
        }
        String smallestUnitStr = null;
        final var smallestUnitValue = member(optionsArg, "smallestUnit", ops);
        if (!(smallestUnitValue instanceof JsUndefined)) {
            smallestUnitStr = JsCoercion.toStr(smallestUnitValue, ops);
        }
        final var mode = modeStr == null ? RoundingMode.TRUNC : RoundingMode.parse(modeStr);
        if (smallestUnitStr != null) {
            final var unit = Unit.parseTemporalUnit(smallestUnitStr);
            requireTimeUnit(unit);
            if (unit == Unit.MINUTE) {
                final var rounded = roundToUnit(receiver.getFields(), Unit.MINUTE, 1, mode);
                return new JsString(pad2(rounded.hour()) + ":" + pad2(rounded.minute()));
            }
            final var rounded = roundToUnit(receiver.getFields(), unit, 1, mode);
            return new JsString(TemporalFormatter.formatTime(rounded, digitsForUnit(unit)));
        }
        if (fsdNumeric != null) {
            final var flooredDouble = Math.floor(fsdNumeric);
            if (Double.isNaN(flooredDouble) || flooredDouble < 0 || flooredDouble > 9) {
                throw new RangeErrorException("fractionalSecondDigits " + fsdNumeric + " floors to " + flooredDouble
                        + " and is out of " + "range");
            }
            final var digits = (int) flooredDouble;
            final var rounded = roundToFractionalDigits(receiver.getFields(), digits, mode);
            return new JsString(TemporalFormatter.formatTime(rounded, digits));
        }
        if (fsdString != null) {
            if (!"auto".equals(fsdString)) {
                throw new RangeErrorException(
                        "fractionalSecondDigits must be 0..9 or \"auto\", got \"" + fsdString + "\"");
            }
            return new JsString(receiver.toString());
        }
        return new JsString(receiver.toString());
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

    private static String pad2(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static JsValue toPlainDateTime(JsTemporalPlainTime receiver, JsValue dateLike, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(dateLike)) {
            throw new TypeErrorException("toPlainDateTime requires a date-like object with year, month and day");
        }
        final var year = (int) toIntegerWithTruncation(requireMember(dateLike, "year", ops), ops);
        final var month = (int) toIntegerWithTruncation(requireMember(dateLike, "month", ops), ops);
        final var day = (int) toIntegerWithTruncation(requireMember(dateLike, "day", ops), ops);
        final var date = IsoCalendar.regulateDate(year, month, day, RegulateOverflow.REJECT);
        return new JsTemporalPlainDateTime(date, receiver.getFields());
    }

    private static JsValue requireMember(JsValue obj, String name, InterpreterOps ops) {
        final var value = member(obj, name, ops);
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required");
        }
        return value;
    }

    private static JsValue getIsoFields(JsTemporalPlainTime receiver) {
        final var fields = receiver.getFields();
        final var result = new JsObject();
        result.set("isoHour", new JsNumber(fields.hour()));
        result.set("isoMinute", new JsNumber(fields.minute()));
        result.set("isoSecond", new JsNumber(fields.second()));
        result.set("isoMillisecond", new JsNumber(fields.millisecond()));
        result.set("isoMicrosecond", new JsNumber(fields.microsecond()));
        result.set("isoNanosecond", new JsNumber(fields.nanosecond()));
        return result;
    }
}

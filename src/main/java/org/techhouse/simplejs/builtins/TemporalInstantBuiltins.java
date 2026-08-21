package org.techhouse.simplejs.builtins;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * All arithmetic, option handling and string parsing for {@code Temporal.Instant} - {@link
 * JsTemporalInstant} itself only holds and derives simple views of its own state, mirroring the
 * {@code JsDate}/{@code DateBuiltins} split.
 *
 * <p>{@code add}/{@code subtract} duck-type their duration argument (read {@code hours}/{@code
 * minutes}/.../{@code nanoseconds} via {@link InterpreterOps#getMember}, requiring the calendar
 * fields to be zero) rather than depending on a concrete {@code JsTemporalDuration} class, since that
 * type lands in a different, concurrently-developed phase; a real {@code Temporal.Duration} instance
 * satisfies this duck type once it exists. {@code until}/{@code since} return the same kind of plain
 * duration-shaped object for the same reason (a genuine {@code Temporal.Duration} construction can
 * replace it once that type is merged). {@link #toZonedDateTimeISO} returns a real {@code
 * Temporal.ZonedDateTime} (phase T7).
 */
public final class TemporalInstantBuiltins {
    public static final List<String> NAMES = List.of("add", "subtract", "until", "since", "round", "equals", "toString",
            "toJSON", "toLocaleString", "toZonedDateTimeISO", "valueOf");

    private static final BigInteger NANOS_PER_HOUR = BigInteger.valueOf(3_600_000_000_000L);
    private static final BigInteger NANOS_PER_MINUTE = BigInteger.valueOf(60_000_000_000L);
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger NANOS_PER_MILLI = BigInteger.valueOf(1_000_000L);
    private static final BigInteger NANOS_PER_MICRO = BigInteger.valueOf(1_000L);
    private static final BigInteger NANOS_PER_DAY = BigInteger.valueOf(86_400_000_000_000L);

    private TemporalInstantBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("Instant", (_, args) -> {
            if (JsNativeFunction.currentNewTarget() == null) {
                throw new TypeErrorException("Constructor Temporal.Instant requires 'new'");
            }
            return withNewTargetPrototype(construct(args, ops), ops);
        });
        ctor.setLength(1);
        final var from = new JsNativeFunction("from", (_, args) -> from(arg(args, 0), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare",
                (_, args) -> new JsNumber(compare(toInstant(arg(args, 0), ops), toInstant(arg(args, 1), ops))));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        final var fromEpochMilliseconds = new JsNativeFunction("fromEpochMilliseconds",
                (_, args) -> JsTemporalInstant.fromEpochMilliseconds(JsCoercion.toNumber(arg(args, 0), ops)));
        fromEpochMilliseconds.setLength(1);
        ctor.setProperty("fromEpochMilliseconds", fromEpochMilliseconds);
        final var fromEpochNanoseconds = new JsNativeFunction("fromEpochNanoseconds", (_, args) -> JsTemporalInstant
                .fromEpochNanoseconds(NumberBuiltins.toBigIntValue(arg(args, 0), ops).getValue()));
        fromEpochNanoseconds.setLength(1);
        ctor.setProperty("fromEpochNanoseconds", fromEpochNanoseconds);
        return ctor;
    }

    // OrdinaryCreateFromConstructor, mirroring DateBuiltins' own helper: a `class X extends
    // Temporal.Instant` subclass instance links to Ctor.prototype instead of the intrinsic one.
    private static JsValue withNewTargetPrototype(JsTemporalInstant constructed, InterpreterOps ops) {
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

    private static JsTemporalInstant construct(List<JsValue> args, InterpreterOps ops) {
        final var value = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var epochNanoseconds = NumberBuiltins.toBigIntValue(value, ops).getValue();
        return JsTemporalInstant.fromEpochNanoseconds(epochNanoseconds);
    }

    public static void installAccessors(JsObject proto) {
        final var millis = new JsNativeFunction("get epochMilliseconds",
                (thisArg, _) -> new JsNumber(requireInstant(thisArg, "epochMilliseconds").epochMillisecondsLong()));
        millis.setLength(0);
        proto.defineAccessor("epochMilliseconds", millis, null);
        proto.setFlags("epochMilliseconds", new JsObject.PropertyFlags(false, false, true));

        final var nanoseconds = new JsNativeFunction("get epochNanoseconds",
                (thisArg, _) -> new JsBigInt(requireInstant(thisArg, "epochNanoseconds").epochNanoseconds()));
        nanoseconds.setLength(0);
        proto.defineAccessor("epochNanoseconds", nanoseconds, null);
        proto.setFlags("epochNanoseconds", new JsObject.PropertyFlags(false, false, true));
    }

    public static JsValue getMethod(JsTemporalInstant receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "add" -> new JsNativeFunction("add", (_, args) -> addOrSubtract(receiver, arg(args, 0), ops, 1));
            case "subtract" ->
                new JsNativeFunction("subtract", (_, args) -> addOrSubtract(receiver, arg(args, 0), ops, -1));
            case "until" ->
                new JsNativeFunction("until", (_, args) -> untilOrSince(receiver, arg(args, 0), arg(args, 1), ops, 1));
            case "since" ->
                new JsNativeFunction("since", (_, args) -> untilOrSince(receiver, arg(args, 0), arg(args, 1), ops, -1));
            case "round" -> new JsNativeFunction("round", (_, args) -> round(receiver, arg(args, 0), ops));
            case "equals" -> new JsNativeFunction("equals",
                    (_, args) -> JsBoolean.of(receiver.isEqualTo(toInstant(arg(args, 0), ops))));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "toZonedDateTimeISO" ->
                new JsNativeFunction("toZonedDateTimeISO", (_, args) -> toZonedDateTimeISO(receiver, arg(args, 0)));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException(
                        "Temporal.Instant does not support valueOf; use compare() or equals() instead");
            });
            default -> null;
        };
    }

    private static JsTemporalInstant requireInstant(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalInstant instant) {
            return instant;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalInstant wrapped) {
            return wrapped;
        }
        throw new TypeErrorException("Temporal.Instant.prototype." + method + " called on an incompatible receiver");
    }

    private static JsValue addOrSubtract(JsTemporalInstant receiver, JsValue durationLike, InterpreterOps ops,
            int sign) {
        final var deltaNanos = durationTimeNanos(durationLike, ops).multiply(BigInteger.valueOf(sign));
        return JsTemporalInstant.fromEpochNanoseconds(receiver.epochNanoseconds().add(deltaNanos));
    }

    // ToTemporalDuration: a real Temporal.Duration, an ISO 8601 duration string, or a plain
    // duration-like object (requiring at least one of the ten recognized properties); the calendar
    // fields must be zero once parsed - Instant arithmetic is defined purely in exact nanoseconds, so
    // a "day" (or larger) has no fixed meaning without a calendar/time zone attached.
    private static BigInteger durationTimeNanos(JsValue durationLike, InterpreterOps ops) {
        final var fields = toDurationFields(durationLike, ops);
        requireZero(fields.years(), "years");
        requireZero(fields.months(), "months");
        requireZero(fields.weeks(), "weeks");
        requireZero(fields.days(), "days");
        return exact(fields.hours()).multiply(NANOS_PER_HOUR).add(exact(fields.minutes()).multiply(NANOS_PER_MINUTE))
                .add(exact(fields.seconds()).multiply(NANOS_PER_SECOND))
                .add(exact(fields.milliseconds()).multiply(NANOS_PER_MILLI))
                .add(exact(fields.microseconds()).multiply(NANOS_PER_MICRO)).add(exact(fields.nanoseconds()));
    }

    // A duration field can be a double far beyond long's range (e.g. 1.728e22 nanoseconds in a
    // minimum-to-maximum-instant test) - narrowing to (long) first would silently saturate/lose
    // precision, so the double is converted to BigInteger directly.
    private static BigInteger exact(double value) {
        return java.math.BigDecimal.valueOf(value).toBigInteger();
    }

    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsString s) {
            return TemporalParser.parseDuration(s.getValue());
        }
        if (!InterpreterUtils.isObjectLike(value) || ops == null) {
            throw new TypeErrorException("Temporal.Instant arithmetic requires a Duration-like object");
        }
        // PrepareTemporalFields reads the recognized properties alphabetically, not in the
        // constructor's years-first positional order.
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

    private static void requireZero(double value, String name) {
        if (value != 0) {
            throw new RangeErrorException(
                    "Temporal.Instant arithmetic requires a zero '" + name + "' duration field, got " + value);
        }
    }

    // Instant.prototype.until/since only accept hour-and-smaller units for smallestUnit/largestUnit
    // per spec: a "day" has no fixed length without a calendar/time zone attached, unlike round()
    // below, where an explicit smallestUnit:"day" is exactly 86,400 seconds.
    private static JsValue untilOrSince(JsTemporalInstant receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops, int sign) {
        final var other = toInstant(otherArg, ops);
        if (!(optionsArg instanceof JsUndefined) && !InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        final var options = optionsArg instanceof JsUndefined ? null : optionsArg;
        // Every option is read (and coerced, observably calling valueOf/toString) in this fixed
        // order - largestUnit, roundingIncrement, roundingMode, smallestUnit - before any
        // algorithmic validation.
        final var largestUnitRaw = unitOptionOrAuto(options, "largestUnit", null, ops);
        final var increment = incrementOption(options, ops);
        final var mode = roundingModeOption(options, RoundingMode.TRUNC, ops);
        final var smallestUnit = unitOption(options, "smallestUnit", Unit.NANOSECOND, ops);
        // largestUnit defaults to (and "auto" resolves to) whichever of smallestUnit/second is
        // coarser, so a smallestUnit larger than the usual "second" default (e.g. "hours") doesn't
        // spuriously conflict with it.
        final var largestUnitDefault = smallestUnit.isLargerThan(Unit.SECOND) ? smallestUnit : Unit.SECOND;
        final var largestUnit = largestUnitRaw == null ? largestUnitDefault : largestUnitRaw;
        if (smallestUnit.isLargerThan(Unit.HOUR) || largestUnit.isLargerThan(Unit.HOUR)) {
            throw new RangeErrorException("Temporal.Instant.prototype.until/since only accept hour-and-smaller units");
        }
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        validateIncrementForUnit(smallestUnit, increment);
        var deltaNanos = other.epochNanoseconds().subtract(receiver.epochNanoseconds())
                .multiply(BigInteger.valueOf(sign));
        if (smallestUnit != Unit.NANOSECOND || increment != 1) {
            deltaNanos = roundNanoseconds(deltaNanos,
                    nanosPerUnit(smallestUnit).multiply(BigInteger.valueOf(increment)), mode);
        }
        return decomposeDuration(deltaNanos, largestUnit);
    }

    private static JsTemporalDuration decomposeDuration(BigInteger signedNanos, Unit largestUnit) {
        final var sign = signedNanos.signum();
        var remaining = signedNanos.abs();
        long hours = 0;
        long minutes = 0;
        long seconds = 0;
        long millis = 0;
        long micros = 0;
        if (Unit.HOUR.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_HOUR);
            hours = dm[0].longValueExact();
            remaining = dm[1];
        }
        if (Unit.MINUTE.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_MINUTE);
            minutes = dm[0].longValueExact();
            remaining = dm[1];
        }
        if (Unit.SECOND.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_SECOND);
            seconds = dm[0].longValueExact();
            remaining = dm[1];
        }
        if (Unit.MILLISECOND.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_MILLI);
            millis = dm[0].longValueExact();
            remaining = dm[1];
        }
        if (Unit.MICROSECOND.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_MICRO);
            micros = dm[0].longValueExact();
            remaining = dm[1];
        }
        final var nanos = remaining.longValueExact();
        return new JsTemporalDuration(new DurationFields(0, 0, 0, 0, sign * hours, sign * minutes, sign * seconds,
                sign * millis, sign * micros, sign * nanos));
    }

    // round() accepts day-and-smaller units (unlike until/since above): a "day" is a fixed,
    // calendar-independent 86,400 seconds when used as an explicit rounding unit here.
    // GetTemporalUnit's "string shorthand" convenience: a bare string argument is equivalent to
    // { smallestUnit: <string> }, matching the other Temporal round() methods.
    private static JsValue roundOptionsObject(JsValue value) {
        if (value instanceof JsString) {
            final var obj = new JsObject();
            obj.setProto(null);
            obj.set("smallestUnit", value);
            return obj;
        }
        return value;
    }

    private static JsValue round(JsTemporalInstant receiver, JsValue optionsArg, InterpreterOps ops) {
        final var options = roundOptionsObject(optionsArg);
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("Temporal.Instant.prototype.round requires an options object");
        }
        // Every option is read (and coerced, observably calling valueOf/toString) before any
        // algorithmic validation - including the smallestUnit/roundingIncrement compatibility check.
        final var increment = incrementOption(options, ops);
        final var mode = roundingModeOption(options, RoundingMode.HALF_EXPAND, ops);
        final var smallestUnitValue = ops.getMember(options, new JsString("smallestUnit"));
        if (smallestUnitValue == null || smallestUnitValue instanceof JsUndefined) {
            throw new RangeErrorException("round() requires a smallestUnit option");
        }
        final var smallestUnit = Unit.parseTemporalUnit(JsCoercion.toStr(smallestUnitValue, ops));
        if (smallestUnit.isLargerThan(Unit.HOUR)) {
            throw new RangeErrorException(
                    "Invalid smallestUnit for Temporal.Instant.prototype.round: " + smallestUnit.singular());
        }
        validateRoundIncrementForDay(smallestUnit, increment);
        final var incrementNanos = nanosPerUnit(smallestUnit).multiply(BigInteger.valueOf(increment));
        return JsTemporalInstant
                .fromEpochNanoseconds(roundNanosecondsAsIfPositive(receiver.epochNanoseconds(), incrementNanos, mode));
    }

    // Unlike Duration rounding (bounded by the immediately-larger unit, e.g. minute <= 60), an
    // Instant has no calendar context: its roundingIncrement is only required to divide evenly into
    // a solar day (86400 SI seconds) in the target unit - so e.g. smallestUnit "minute" allows up to
    // 1440, not just 60.
    private static void validateRoundIncrementForDay(Unit unit, long increment) {
        final var maximum = NANOS_PER_DAY.divide(nanosPerUnit(unit)).longValueExact();
        if (increment < 1 || increment > maximum || maximum % increment != 0) {
            throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit " + unit.singular());
        }
    }

    private static JsValue toStringMethod(JsTemporalInstant receiver, JsValue optionsArg, InterpreterOps ops) {
        if (!(optionsArg instanceof JsUndefined) && !InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        final var options = optionsArg instanceof JsUndefined ? null : optionsArg;
        // Every option is read (and coerced, observably calling valueOf/toString) in this fixed
        // order - fractionalSecondDigits, roundingMode, smallestUnit, timeZone - before any
        // algorithmic validation, including the smallestUnit-is-a-date-unit check below. Unlike
        // round(), toString() never reads a roundingIncrement option (implicitly always 1).
        var fractionDigits = fractionalSecondDigitsOption(options, ops);
        final var mode = roundingModeOption(options, RoundingMode.TRUNC, ops);
        final var smallestUnit = unitOption(options, "smallestUnit", null, ops);
        final var zone = timeZoneOption(options, ops);
        if (smallestUnit != null && smallestUnit.isLargerThan(Unit.MINUTE)) {
            throw new RangeErrorException(
                    "Invalid smallestUnit for Temporal.Instant.prototype.toString: " + smallestUnit.singular());
        }
        var instant = receiver;
        var minutePrecision = false;
        if (smallestUnit != null) {
            instant = JsTemporalInstant.fromEpochNanoseconds(
                    roundNanosecondsAsIfPositive(receiver.epochNanoseconds(), nanosPerUnit(smallestUnit), mode));
            if (smallestUnit == Unit.MINUTE) {
                minutePrecision = true;
            } else {
                fractionDigits = digitsFor(smallestUnit);
            }
        } else if (fractionDigits != null) {
            final var incrementNanos = BigInteger.TEN.pow(9 - fractionDigits);
            instant = JsTemporalInstant.fromEpochNanoseconds(
                    roundNanosecondsAsIfPositive(receiver.epochNanoseconds(), incrementNanos, mode));
        }
        final var offset = zone == null ? ZoneOffset.UTC : zone.getRules().getOffset(instant.toJavaInstant());
        final var fields = instant.isoFieldsAt(offset);
        final var offsetText = zone == null ? "Z" : TemporalFormatter.formatOffset(offset);
        final var timeText = minutePrecision
                ? TemporalFormatter.formatTimeMinutePrecision(fields.time())
                : TemporalFormatter.formatTime(fields.time(), fractionDigits);
        return new JsString(TemporalFormatter.formatDate(fields.date()) + "T" + timeText + offsetText);
    }

    private static int digitsFor(Unit unit) {
        return switch (unit) {
            case SECOND -> 0;
            case MILLISECOND -> 3;
            case MICROSECOND -> 6;
            case NANOSECOND -> 9;
            default -> throw new RangeErrorException("Unsupported smallestUnit: " + unit.singular());
        };
    }

    // GetTemporalFractionalSecondDigitsOption: a value that isn't already type Number is converted
    // via ToString and must equal "auto" exactly (a numeric *string* like "3" is rejected, and never
    // reaches ToNumber) - it does not fall back to a general ToNumber coercion the way most numeric
    // options do.
    private static Integer fractionalSecondDigitsOption(JsValue options, InterpreterOps ops) {
        if (options == null || options instanceof JsUndefined) {
            return null;
        }
        final var raw = ops.getMember(options, new JsString("fractionalSecondDigits"));
        if (raw == null || raw instanceof JsUndefined) {
            return null;
        }
        if (!(raw instanceof JsNumber number)) {
            final var str = JsCoercion.toStr(raw, ops);
            if (!"auto".equals(str)) {
                throw new RangeErrorException(JsCoercion.toStr(raw) + " is not a number and converts to the string '"
                        + str + "' which is not valid for fractionalSecondDigits");
            }
            return null;
        }
        final var value = number.getValue();
        if (!Double.isFinite(value)) {
            throw new RangeErrorException("fractionalSecondDigits must be 'auto' or an integer 0..9");
        }
        final var floored = Math.floor(value);
        if (floored < 0 || floored > 9) {
            throw new RangeErrorException(
                    "fractionalSecondDigits " + value + " floors to " + (long) floored + " and is out of range");
        }
        return (int) floored;
    }

    private static ZoneId timeZoneOption(JsValue options, InterpreterOps ops) {
        if (options == null || options instanceof JsUndefined) {
            return null;
        }
        final var raw = ops.getMember(options, new JsString("timeZone"));
        if (raw == null || raw instanceof JsUndefined) {
            return null;
        }
        if (!(raw instanceof JsString s)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        return TemporalZonedDateTimeBuiltins.zoneOf(TemporalParser.parseTimeZoneIdentifierFlexible(s.getValue()));
    }

    // Temporal.ZonedDateTime (phase T7) - a real instance, fixed "iso8601" calendar per this method's
    // own name.
    private static JsValue toZonedDateTimeISO(JsTemporalInstant receiver, JsValue timeZoneArg) {
        if (timeZoneArg == null || timeZoneArg instanceof JsUndefined) {
            throw new TypeErrorException("Temporal.Instant.prototype.toZonedDateTimeISO requires a timeZone argument");
        }
        if (!(timeZoneArg instanceof JsString s)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        final var id = TemporalParser.parseTimeZoneIdentifierFlexible(s.getValue());
        return new JsTemporalZonedDateTime(receiver.epochSecondsPart(), receiver.nanoAdjustment(),
                TemporalZonedDateTimeBuiltins.zoneOf(id), id);
    }

    private static long incrementOption(JsValue options, InterpreterOps ops) {
        if (options == null || options instanceof JsUndefined) {
            return 1;
        }
        final var raw = ops.getMember(options, new JsString("roundingIncrement"));
        if (raw == null || raw instanceof JsUndefined) {
            return 1;
        }
        final var value = JsCoercion.toNumber(raw, ops);
        if (!Double.isFinite(value)) {
            throw new RangeErrorException("roundingIncrement must be a positive integer");
        }
        final var truncated = (long) value;
        if (truncated < 1 || truncated > 1_000_000_000L) {
            throw new RangeErrorException("roundingIncrement must be a positive integer");
        }
        return truncated;
    }

    // Duration rounding (since/until) validates the increment exclusively - it must be strictly less
    // than the next-highest unit's conversion factor, not merely divide evenly into it - unlike
    // Instant.round()'s inclusive day-fraction check (validateRoundIncrementForDay).
    private static void validateIncrementForUnit(Unit unit, long increment) {
        final var max = maxIncrementFor(unit);
        if (increment < 1 || increment >= max || max % increment != 0) {
            throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit " + unit.singular());
        }
    }

    private static long maxIncrementFor(Unit unit) {
        return switch (unit) {
            case DAY -> 1;
            case HOUR -> 24;
            case MINUTE, SECOND -> 60;
            default -> 1000;
        };
    }

    private static RoundingMode roundingModeOption(JsValue options, RoundingMode fallback, InterpreterOps ops) {
        if (options == null || options instanceof JsUndefined) {
            return fallback;
        }
        final var raw = ops.getMember(options, new JsString("roundingMode"));
        if (raw == null || raw instanceof JsUndefined) {
            return fallback;
        }
        return RoundingMode.parse(JsCoercion.toStr(raw, ops));
    }

    private static Unit unitOption(JsValue options, String key, Unit fallback, InterpreterOps ops) {
        if (options == null || options instanceof JsUndefined) {
            return fallback;
        }
        final var raw = ops.getMember(options, new JsString(key));
        if (raw == null || raw instanceof JsUndefined) {
            return fallback;
        }
        return Unit.parseTemporalUnit(JsCoercion.toStr(raw, ops));
    }

    private static Unit unitOptionOrAuto(JsValue options, String key, Unit fallback, InterpreterOps ops) {
        if (options == null || options instanceof JsUndefined) {
            return fallback;
        }
        final var raw = ops.getMember(options, new JsString(key));
        if (raw == null || raw instanceof JsUndefined) {
            return fallback;
        }
        final var str = JsCoercion.toStr(raw, ops);
        return "auto".equals(str) ? fallback : Unit.parseTemporalUnit(str);
    }

    private static BigInteger nanosPerUnit(Unit unit) {
        return switch (unit) {
            case DAY -> NANOS_PER_DAY;
            case HOUR -> NANOS_PER_HOUR;
            case MINUTE -> NANOS_PER_MINUTE;
            case SECOND -> NANOS_PER_SECOND;
            case MILLISECOND -> NANOS_PER_MILLI;
            case MICROSECOND -> NANOS_PER_MICRO;
            case NANOSECOND -> BigInteger.ONE;
            default -> throw new RangeErrorException("Unsupported unit for Temporal.Instant: " + unit.singular());
        };
    }

    // A small, self-contained BigInteger rounding routine (structurally the same rules as
    // DurationMath's, which is deliberately for Duration-sized values): an absolute epoch-nanosecond
    // total already exceeds double precision, so this stays in BigInteger throughout rather than
    // reusing DurationMath's double-based DurationFields plumbing.
    private static BigInteger roundNanoseconds(BigInteger value, BigInteger increment, RoundingMode mode) {
        final var divRem = value.divideAndRemainder(increment);
        final var quotient = divRem[0];
        final var remainder = divRem[1];
        if (remainder.signum() == 0) {
            return value;
        }
        final var sign = value.signum();
        final var remainderAbs = remainder.abs();
        final var cmp = remainderAbs.shiftLeft(1).compareTo(increment);
        final var roundedQuotient = switch (mode) {
            case TRUNC -> quotient;
            case CEIL -> sign > 0 ? quotient.add(BigInteger.ONE) : quotient;
            case FLOOR -> sign < 0 ? quotient.subtract(BigInteger.ONE) : quotient;
            case EXPAND -> awayFromZero(quotient, sign);
            case HALF_EXPAND -> cmp >= 0 ? awayFromZero(quotient, sign) : quotient;
            case HALF_TRUNC -> cmp > 0 ? awayFromZero(quotient, sign) : quotient;
            case HALF_CEIL -> halfDirectional(quotient, cmp, sign, true);
            case HALF_FLOOR -> halfDirectional(quotient, cmp, sign, false);
            case HALF_EVEN -> halfEven(quotient, cmp, sign);
        };
        return roundedQuotient.multiply(increment);
    }

    // RoundNumberToIncrementAsIfPositive: Instant.prototype.round/toString round the raw (possibly
    // negative) epoch-nanosecond value directly, unlike a signed Duration's magnitude-relative
    // rounding (roundNanoseconds above, used by since/until). Under this convention every mode
    // resolves purely in terms of the true floor/ceiling bracket (never in terms of shrinking/growing
    // the value's magnitude the way "trunc"/"expand" do for a signed Duration) - so the tie-breaking
    // comparison must use the true (floor-based, always non-negative) remainder; Java's
    // divideAndRemainder truncates toward zero, whose remainder is the *negated distance to the
    // ceiling* for a negative value, not the distance to the floor, if left uncorrected.
    private static BigInteger roundNanosecondsAsIfPositive(BigInteger value, BigInteger increment, RoundingMode mode) {
        final var divRem = value.divideAndRemainder(increment);
        var floorQuotient = divRem[0];
        var floorRemainder = divRem[1];
        if (floorRemainder.signum() < 0) {
            floorQuotient = floorQuotient.subtract(BigInteger.ONE);
            floorRemainder = floorRemainder.add(increment);
        }
        if (floorRemainder.signum() == 0) {
            return value;
        }
        final var ceilQuotient = floorQuotient.add(BigInteger.ONE);
        final var cmp = floorRemainder.shiftLeft(1).compareTo(increment);
        final var roundedQuotient = switch (mode) {
            case TRUNC, FLOOR -> floorQuotient;
            case EXPAND, CEIL -> ceilQuotient;
            case HALF_EXPAND, HALF_CEIL -> cmp >= 0 ? ceilQuotient : floorQuotient;
            case HALF_TRUNC, HALF_FLOOR -> cmp > 0 ? ceilQuotient : floorQuotient;
            case HALF_EVEN -> cmp > 0 || (cmp == 0 && floorQuotient.testBit(0)) ? ceilQuotient : floorQuotient;
        };
        return roundedQuotient.multiply(increment);
    }

    private static BigInteger halfDirectional(BigInteger quotient, int cmp, int sign, boolean tieTowardPositive) {
        if (cmp > 0) {
            return awayFromZero(quotient, sign);
        }
        if (cmp == 0) {
            final var tieGoesAway = tieTowardPositive ? sign > 0 : sign < 0;
            return tieGoesAway ? awayFromZero(quotient, sign) : quotient;
        }
        return quotient;
    }

    private static BigInteger halfEven(BigInteger quotient, int cmp, int sign) {
        if (cmp > 0) {
            return awayFromZero(quotient, sign);
        }
        if (cmp == 0) {
            return quotient.testBit(0) ? awayFromZero(quotient, sign) : quotient;
        }
        return quotient;
    }

    private static BigInteger awayFromZero(BigInteger quotient, int sign) {
        return sign >= 0 ? quotient.add(BigInteger.ONE) : quotient.subtract(BigInteger.ONE);
    }

    // Unlike the internal toInstant coercion (used by compare/equals/since/until, where identity
    // never matters), Temporal.Instant.from() must return a fresh copy even when given an existing
    // Instant - never the same reference.
    private static JsValue from(JsValue value, InterpreterOps ops) {
        return JsTemporalInstant.fromEpochNanoseconds(toInstant(value, ops).epochNanoseconds());
    }

    private static int compare(JsTemporalInstant a, JsTemporalInstant b) {
        return Integer.signum(a.compareEpoch(b));
    }

    // ToTemporalInstant: accepts a Temporal.Instant instance (including a subclass wrapper) or an ISO
    // instant string.
    private static JsTemporalInstant toInstant(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalInstant instant) {
            return instant;
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalInstant wrapped) {
            return wrapped;
        }
        // ToTemporalInstant fast path: a ZonedDateTime's instant is read directly (no toString call)
        // rather than round-tripping through its string representation.
        if (value instanceof JsTemporalZonedDateTime zoned) {
            return JsTemporalInstant.fromEpochNanoseconds(zoned.epochNanoseconds());
        }
        if (value instanceof JsObject wrapper
                && wrapper.getPrimitive() instanceof JsTemporalZonedDateTime zonedWrapped) {
            return JsTemporalInstant.fromEpochNanoseconds(zonedWrapped.epochNanoseconds());
        }
        // A non-string primitive (undefined/null/boolean/number/bigint/symbol) is not a valid
        // Instant argument at all and never reaches ToString - only an object (which may still fail
        // to produce a parseable string, a RangeError) or a string itself is attempted.
        if (!(value instanceof JsString) && !InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("Cannot convert value to a Temporal.Instant");
        }
        return fromIsoString(JsCoercion.toStr(value, ops));
    }

    private static JsTemporalInstant fromIsoString(String text) {
        final var parsed = TemporalParser.parseInstant(text);
        final var date = parsed.date();
        final var time = parsed.time();
        final var epochDay = LocalDate.of(date.year(), date.month(), date.day()).toEpochDay();
        final var nanosOfDay = (time.hour() * 3_600L + time.minute() * 60L + time.second()) * 1_000_000_000L
                + time.millisecond() * 1_000_000L + time.microsecond() * 1_000L + time.nanosecond();
        final var offsetNanos = parseOffsetNanos(parsed.offset());
        final var totalNanos = BigInteger.valueOf(epochDay).multiply(NANOS_PER_DAY).add(BigInteger.valueOf(nanosOfDay))
                .subtract(BigInteger.valueOf(offsetNanos));
        return JsTemporalInstant.fromEpochNanoseconds(totalNanos);
    }

    private static long parseOffsetNanos(String offset) {
        if (offset == null || "Z".equals(offset) || "z".equals(offset)) {
            return 0L;
        }
        final var sign = offset.charAt(0) == '-' || offset.charAt(0) == '−' ? -1 : 1;
        final var rest = offset.substring(1).replace(":", "");
        final var hours = Integer.parseInt(rest.substring(0, 2));
        var minutes = 0;
        var seconds = 0;
        var nanos = 0L;
        if (rest.length() > 2) {
            minutes = Integer.parseInt(rest.substring(2, 4));
            if (rest.length() > 4) {
                seconds = Integer.parseInt(rest.substring(4, 6));
                final var dot = rest.indexOf('.');
                if (dot >= 0) {
                    var fraction = rest.substring(dot + 1);
                    fraction = fraction.length() > 9
                            ? fraction.substring(0, 9)
                            : fraction + "0".repeat(9 - fraction.length());
                    nanos = Long.parseLong(fraction);
                }
            }
        }
        final var totalNanos = (hours * 3_600L + minutes * 60L + seconds) * 1_000_000_000L + nanos;
        return sign * totalNanos;
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}

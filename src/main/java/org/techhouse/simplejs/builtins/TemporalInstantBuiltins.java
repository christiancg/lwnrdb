package org.techhouse.simplejs.builtins;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
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
            return withNewTargetPrototype(construct(args), ops);
        });
        ctor.setLength(1);
        ctor.setProperty("from", new JsNativeFunction("from", (_, args) -> from(arg(args, 0), ops)));
        ctor.setProperty("compare", new JsNativeFunction("compare",
                (_, args) -> new JsNumber(compare(toInstant(arg(args, 0), ops), toInstant(arg(args, 1), ops)))));
        ctor.setProperty("fromEpochMilliseconds", new JsNativeFunction("fromEpochMilliseconds",
                (_, args) -> JsTemporalInstant.fromEpochMilliseconds(JsCoercion.toNumber(arg(args, 0), ops))));
        ctor.setProperty("fromEpochNanoseconds", new JsNativeFunction("fromEpochNanoseconds",
                (_, args) -> JsTemporalInstant.fromEpochNanoseconds(requireBigInt(arg(args, 0)))));
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

    private static JsTemporalInstant construct(List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsBigInt bigInt)) {
            throw new TypeErrorException("Constructor Temporal.Instant requires an epochNanoseconds BigInt argument");
        }
        return JsTemporalInstant.fromEpochNanoseconds(bigInt.getValue());
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
            case "toZonedDateTimeISO" -> new JsNativeFunction("toZonedDateTimeISO",
                    (_, args) -> toZonedDateTimeISO(receiver, arg(args, 0), ops));
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

    // Duck-types the argument: any object exposing the Duration time fields works (a real
    // Temporal.Duration or a plain object), and the calendar fields must be zero - Instant arithmetic
    // is defined purely in exact nanoseconds, so a "day" (or larger) has no fixed meaning without a
    // calendar/time zone attached.
    private static BigInteger durationTimeNanos(JsValue durationLike, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(durationLike) || ops == null) {
            throw new TypeErrorException("Temporal.Instant arithmetic requires a Duration-like object");
        }
        requireZeroField(durationLike, "years", ops);
        requireZeroField(durationLike, "months", ops);
        requireZeroField(durationLike, "weeks", ops);
        requireZeroField(durationLike, "days", ops);
        final var hours = numField(durationLike, "hours", ops);
        final var minutes = numField(durationLike, "minutes", ops);
        final var seconds = numField(durationLike, "seconds", ops);
        final var millis = numField(durationLike, "milliseconds", ops);
        final var micros = numField(durationLike, "microseconds", ops);
        final var nanos = numField(durationLike, "nanoseconds", ops);
        return BigInteger.valueOf((long) hours).multiply(NANOS_PER_HOUR)
                .add(BigInteger.valueOf((long) minutes).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf((long) seconds).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf((long) millis).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf((long) micros).multiply(NANOS_PER_MICRO)).add(BigInteger.valueOf((long) nanos));
    }

    private static double numField(JsValue durationLike, String key, InterpreterOps ops) {
        final var raw = ops.getMember(durationLike, new JsString(key));
        if (raw == null || raw instanceof JsUndefined) {
            return 0;
        }
        final var value = JsCoercion.toNumber(raw, ops);
        if (!Double.isFinite(value) || value != Math.floor(value)) {
            throw new RangeErrorException("Temporal duration field '" + key + "' must be an integer");
        }
        return value;
    }

    private static void requireZeroField(JsValue durationLike, String key, InterpreterOps ops) {
        if (numField(durationLike, key, ops) != 0) {
            throw new RangeErrorException(
                    "Temporal.Instant arithmetic does not accept a non-zero '" + key + "' duration field");
        }
    }

    // Instant.prototype.until/since only accept hour-and-smaller units for smallestUnit/largestUnit
    // per spec: a "day" has no fixed length without a calendar/time zone attached, unlike round()
    // below, where an explicit smallestUnit:"day" is exactly 86,400 seconds.
    private static JsValue untilOrSince(JsTemporalInstant receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops, int sign) {
        final var other = toInstant(otherArg, ops);
        final var options = optionsArg instanceof JsObject opts ? opts : null;
        final var smallestUnit = unitOption(options, "smallestUnit", Unit.NANOSECOND, ops);
        final var largestUnit = unitOption(options, "largestUnit", Unit.SECOND, ops);
        if (smallestUnit.isLargerThan(Unit.HOUR) || largestUnit.isLargerThan(Unit.HOUR)) {
            throw new RangeErrorException("Temporal.Instant.prototype.until/since only accept hour-and-smaller units");
        }
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        final var increment = incrementOption(options, ops);
        validateIncrementForUnit(smallestUnit, increment);
        final var mode = roundingModeOption(options, RoundingMode.TRUNC, ops);
        var deltaNanos = other.epochNanoseconds().subtract(receiver.epochNanoseconds())
                .multiply(BigInteger.valueOf(sign));
        if (smallestUnit != Unit.NANOSECOND || increment != 1) {
            deltaNanos = roundNanoseconds(deltaNanos,
                    nanosPerUnit(smallestUnit).multiply(BigInteger.valueOf(increment)), mode);
        }
        return decomposeDuration(deltaNanos, largestUnit);
    }

    private static JsObject decomposeDuration(BigInteger signedNanos, Unit largestUnit) {
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
        final var result = new JsObject();
        result.set("years", new JsNumber(0));
        result.set("months", new JsNumber(0));
        result.set("weeks", new JsNumber(0));
        result.set("days", new JsNumber(0));
        result.set("hours", new JsNumber(sign * hours));
        result.set("minutes", new JsNumber(sign * minutes));
        result.set("seconds", new JsNumber(sign * seconds));
        result.set("milliseconds", new JsNumber(sign * millis));
        result.set("microseconds", new JsNumber(sign * micros));
        result.set("nanoseconds", new JsNumber(sign * nanos));
        result.set("sign", new JsNumber(sign));
        result.set("blank", JsBoolean.of(sign == 0));
        return result;
    }

    // round() accepts day-and-smaller units (unlike until/since above): a "day" is a fixed,
    // calendar-independent 86,400 seconds when used as an explicit rounding unit here.
    private static JsValue round(JsTemporalInstant receiver, JsValue optionsArg, InterpreterOps ops) {
        if (!(optionsArg instanceof JsObject options)) {
            throw new TypeErrorException("Temporal.Instant.prototype.round requires an options object");
        }
        final var smallestUnitValue = ops.getMember(options, new JsString("smallestUnit"));
        if (smallestUnitValue == null || smallestUnitValue instanceof JsUndefined) {
            throw new RangeErrorException("round() requires a smallestUnit option");
        }
        final var smallestUnit = Unit.parseTemporalUnit(JsCoercion.toStr(smallestUnitValue, ops));
        if (smallestUnit.isLargerThan(Unit.DAY)) {
            throw new RangeErrorException(
                    "Invalid smallestUnit for Temporal.Instant.prototype.round: " + smallestUnit.singular());
        }
        final var increment = incrementOption(options, ops);
        validateIncrementForUnit(smallestUnit, increment);
        final var mode = roundingModeOption(options, RoundingMode.HALF_EXPAND, ops);
        final var incrementNanos = nanosPerUnit(smallestUnit).multiply(BigInteger.valueOf(increment));
        return JsTemporalInstant
                .fromEpochNanoseconds(roundNanoseconds(receiver.epochNanoseconds(), incrementNanos, mode));
    }

    private static JsValue toStringMethod(JsTemporalInstant receiver, JsValue optionsArg, InterpreterOps ops) {
        final var options = optionsArg instanceof JsObject opts ? opts : null;
        var instant = receiver;
        final var smallestUnit = unitOption(options, "smallestUnit", null, ops);
        var fractionDigits = fractionalSecondDigitsOption(options, ops);
        if (smallestUnit != null) {
            if (smallestUnit.isLargerThan(Unit.SECOND)) {
                throw new RangeErrorException(
                        "Invalid smallestUnit for Temporal.Instant.prototype.toString: " + smallestUnit.singular());
            }
            final var increment = incrementOption(options, ops);
            validateIncrementForUnit(smallestUnit, increment);
            final var mode = roundingModeOption(options, RoundingMode.TRUNC, ops);
            instant = JsTemporalInstant.fromEpochNanoseconds(roundNanoseconds(receiver.epochNanoseconds(),
                    nanosPerUnit(smallestUnit).multiply(BigInteger.valueOf(increment)), mode));
            fractionDigits = digitsFor(smallestUnit);
        }
        final var zone = timeZoneOption(options, ops);
        final var offset = zone == null ? ZoneOffset.UTC : zone.getRules().getOffset(instant.toJavaInstant());
        final var fields = instant.isoFieldsAt(offset);
        final var offsetText = zone == null ? "Z" : TemporalFormatter.formatOffset(offset);
        return new JsString(TemporalFormatter.formatDate(fields.date()) + "T"
                + TemporalFormatter.formatTime(fields.time(), fractionDigits) + offsetText);
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

    private static Integer fractionalSecondDigitsOption(JsObject options, InterpreterOps ops) {
        if (options == null) {
            return null;
        }
        final var raw = ops.getMember(options, new JsString("fractionalSecondDigits"));
        if (raw == null || raw instanceof JsUndefined) {
            return null;
        }
        if (raw instanceof JsString s && "auto".equals(s.getValue())) {
            return null;
        }
        final var value = JsCoercion.toNumber(raw, ops);
        if (!Double.isFinite(value) || value < 0 || value > 9 || value != Math.floor(value)) {
            throw new RangeErrorException("fractionalSecondDigits must be 'auto' or an integer 0..9");
        }
        return (int) value;
    }

    private static ZoneId timeZoneOption(JsObject options, InterpreterOps ops) {
        if (options == null) {
            return null;
        }
        final var raw = ops.getMember(options, new JsString("timeZone"));
        if (raw == null || raw instanceof JsUndefined) {
            return null;
        }
        return zoneOf(JsCoercion.toStr(raw, ops));
    }

    private static ZoneId zoneOf(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid time zone identifier: " + id);
        }
    }

    // Temporal.ZonedDateTime (phase T7) - a real instance, fixed "iso8601" calendar per this method's
    // own name.
    private static JsValue toZonedDateTimeISO(JsTemporalInstant receiver, JsValue timeZoneArg, InterpreterOps ops) {
        if (timeZoneArg == null || timeZoneArg instanceof JsUndefined) {
            throw new TypeErrorException("Temporal.Instant.prototype.toZonedDateTimeISO requires a timeZone argument");
        }
        final var id = TemporalParser.parseTimeZoneIdentifier(JsCoercion.toStr(timeZoneArg, ops));
        return new JsTemporalZonedDateTime(receiver.epochSecondsPart(), receiver.nanoAdjustment(), zoneOf(id), id);
    }

    private static long incrementOption(JsObject options, InterpreterOps ops) {
        if (options == null) {
            return 1;
        }
        final var raw = ops.getMember(options, new JsString("roundingIncrement"));
        if (raw == null || raw instanceof JsUndefined) {
            return 1;
        }
        final var value = JsCoercion.toNumber(raw, ops);
        if (!Double.isFinite(value) || value < 1 || value != Math.floor(value)) {
            throw new RangeErrorException("roundingIncrement must be a positive integer");
        }
        return (long) value;
    }

    private static void validateIncrementForUnit(Unit unit, long increment) {
        final var max = maxIncrementFor(unit);
        if (increment < 1 || increment > max || max % increment != 0) {
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

    private static RoundingMode roundingModeOption(JsObject options, RoundingMode fallback, InterpreterOps ops) {
        if (options == null) {
            return fallback;
        }
        final var raw = ops.getMember(options, new JsString("roundingMode"));
        if (raw == null || raw instanceof JsUndefined) {
            return fallback;
        }
        return RoundingMode.parse(JsCoercion.toStr(raw, ops));
    }

    private static Unit unitOption(JsObject options, String key, Unit fallback, InterpreterOps ops) {
        if (options == null) {
            return fallback;
        }
        final var raw = ops.getMember(options, new JsString(key));
        if (raw == null || raw instanceof JsUndefined) {
            return fallback;
        }
        return Unit.parseTemporalUnit(JsCoercion.toStr(raw, ops));
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
            case HALF_EXPAND -> cmp >= 0 ? awayFromZero(quotient, sign) : quotient;
            case HALF_TRUNC -> cmp > 0 ? awayFromZero(quotient, sign) : quotient;
            case HALF_CEIL -> halfDirectional(quotient, cmp, sign, true);
            case HALF_FLOOR -> halfDirectional(quotient, cmp, sign, false);
            case HALF_EVEN -> halfEven(quotient, cmp, sign);
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

    private static JsValue from(JsValue value, InterpreterOps ops) {
        return toInstant(value, ops);
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
        final var minutes = Integer.parseInt(rest.substring(2, 4));
        var seconds = 0;
        var nanos = 0L;
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
        final var totalNanos = (hours * 3_600L + minutes * 60L + seconds) * 1_000_000_000L + nanos;
        return sign * totalNanos;
    }

    private static BigInteger requireBigInt(JsValue value) {
        if (value instanceof JsBigInt bigInt) {
            return bigInt.getValue();
        }
        throw new TypeErrorException("Temporal.Instant.fromEpochNanoseconds requires a BigInt argument");
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}

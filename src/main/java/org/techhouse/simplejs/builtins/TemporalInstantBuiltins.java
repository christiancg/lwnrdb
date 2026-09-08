package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTargetOrSubclassInstance;
import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;
import static org.techhouse.simplejs.builtins.temporal.InstantRounding.incrementOption;
import static org.techhouse.simplejs.builtins.temporal.InstantRounding.round;
import static org.techhouse.simplejs.builtins.temporal.InstantRounding.roundNanoseconds;
import static org.techhouse.simplejs.builtins.temporal.InstantRounding.roundNanosecondsAsIfPositive;
import static org.techhouse.simplejs.builtins.temporal.InstantRounding.roundingModeOption;
import static org.techhouse.simplejs.builtins.temporal.InstantRounding.unitOption;
import static org.techhouse.simplejs.builtins.temporal.InstantRounding.unitOptionOrAuto;
import static org.techhouse.simplejs.builtins.temporal.InstantRounding.validateIncrementForUnit;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.orZeroDuration;
import static org.techhouse.simplejs.builtins.temporal.TemporalFields.readDurationField;
import static org.techhouse.simplejs.builtins.temporal.TemporalTimeFields.nanosPerUnit;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_DAY;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_HOUR;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MICRO;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MILLI;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MINUTE;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.techhouse.simplejs.builtins.temporal.ZonedDateTimeZones;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.DurationStringParser;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.internal.temporal.TimeZoneStringParser;
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

public final class TemporalInstantBuiltins {
    public static final List<String> NAMES = List.of("add", "subtract", "until", "since", "round", "equals", "toString",
            "toJSON", "toLocaleString", "toZonedDateTimeISO", "valueOf");

    private TemporalInstantBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("Instant", (thisArg, args) -> {
            requireNewTargetOrSubclassInstance("Temporal.Instant", thisArg);
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
        proto.setFlags("epochMilliseconds", JsObject.PropertyFlags.TAG);

        final var nanoseconds = new JsNativeFunction("get epochNanoseconds",
                (thisArg, _) -> new JsBigInt(requireInstant(thisArg, "epochNanoseconds").epochNanoseconds()));
        nanoseconds.setLength(0);
        proto.defineAccessor("epochNanoseconds", nanoseconds, null);
        proto.setFlags("epochNanoseconds", JsObject.PropertyFlags.TAG);
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

    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static BigInteger exact(double value) {
        return new java.math.BigDecimal(value).toBigInteger();
    }

    private static DurationFields toDurationFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalDuration duration) {
            return duration.getFields();
        }
        if (value instanceof JsString s) {
            return DurationStringParser.parseDuration(s.getValue());
        }
        if (!InterpreterUtils.isObjectLike(value) || ops == null) {
            throw new TypeErrorException("Temporal.Instant arithmetic requires a Duration-like object");
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

    private static void requireZero(double value, String name) {
        if (value != 0) {
            throw new RangeErrorException(
                    "Temporal.Instant arithmetic requires a zero '" + name + "' duration field, got " + value);
        }
    }

    private static JsValue untilOrSince(JsTemporalInstant receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops, int sign) {
        final var other = toInstant(otherArg, ops);
        if (!(optionsArg instanceof JsUndefined) && !InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        final var options = optionsArg instanceof JsUndefined ? null : optionsArg;
        final var largestUnitRaw = unitOptionOrAuto(options, ops);
        final var increment = incrementOption(options, ops);
        final var mode = roundingModeOption(options, RoundingMode.TRUNC, ops);
        final var smallestUnit = unitOption(options, Unit.NANOSECOND, ops);
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

    private static JsValue toStringMethod(JsTemporalInstant receiver, JsValue optionsArg, InterpreterOps ops) {
        if (!(optionsArg instanceof JsUndefined) && !InterpreterUtils.isObjectLike(optionsArg)) {
            throw new TypeErrorException("options must be an object");
        }
        final var options = optionsArg instanceof JsUndefined ? null : optionsArg;
        var fractionDigits = fractionalSecondDigitsOption(options, ops);
        final var mode = roundingModeOption(options, RoundingMode.TRUNC, ops);
        final var smallestUnit = unitOption(options, null, ops);
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
        return ZonedDateTimeZones.zoneOf(TimeZoneStringParser.parseTimeZoneIdentifierFlexible(s.getValue()));
    }

    private static JsValue toZonedDateTimeISO(JsTemporalInstant receiver, JsValue timeZoneArg) {
        if (timeZoneArg == null || timeZoneArg instanceof JsUndefined) {
            throw new TypeErrorException("Temporal.Instant.prototype.toZonedDateTimeISO requires a timeZone argument");
        }
        if (!(timeZoneArg instanceof JsString s)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        final var id = TimeZoneStringParser.parseTimeZoneIdentifierFlexible(s.getValue());
        return new JsTemporalZonedDateTime(receiver.epochSecondsPart(), receiver.nanoAdjustment(),
                ZonedDateTimeZones.zoneOf(id), id);
    }

    private static JsValue from(JsValue value, InterpreterOps ops) {
        return JsTemporalInstant.fromEpochNanoseconds(toInstant(value, ops).epochNanoseconds());
    }

    private static int compare(JsTemporalInstant a, JsTemporalInstant b) {
        return Integer.signum(a.compareEpoch(b));
    }

    private static JsTemporalInstant toInstant(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalInstant instant) {
            return instant;
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalInstant wrapped) {
            return wrapped;
        }
        if (value instanceof JsTemporalZonedDateTime zoned) {
            return JsTemporalInstant.fromEpochNanoseconds(zoned.epochNanoseconds());
        }
        if (value instanceof JsObject wrapper
                && wrapper.getPrimitive() instanceof JsTemporalZonedDateTime zonedWrapped) {
            return JsTemporalInstant.fromEpochNanoseconds(zonedWrapped.epochNanoseconds());
        }
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

}

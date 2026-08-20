package org.techhouse.simplejs.builtins;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
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
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;
import org.techhouse.simplejs.internal.temporal.TemporalParser;
import org.techhouse.simplejs.internal.temporal.Unit;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
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
 * {@code Temporal.ZonedDateTime}: an exact instant composed with a {@code java.time.ZoneId} and the
 * fixed {@code "iso8601"} calendar - the union of {@code Temporal.Instant}'s and {@code
 * Temporal.PlainDateTime}'s method surface plus time-zone-specific extras ({@code withTimeZone},
 * {@code hoursInDay}, {@code offset}/{@code offsetNanoseconds}, {@code getTimeZoneTransition}). Real
 * {@code Temporal.PlainDate}/{@code PlainTime}/{@code PlainDateTime}/{@code PlainYearMonth}/{@code
 * PlainMonthDay}/{@code Instant} instances are returned throughout (all six landed in earlier
 * phases); nothing here duck-types a not-yet-existing type.
 *
 * <p><b>Disambiguation.</b> Converting local wall-clock fields to an instant can land on a DST gap
 * (a nonexistent local time, e.g. 2:30 during a spring-forward) or a fold (an ambiguous local time
 * that occurs twice, e.g. 1:30 during a fall-back). {@link #resolveLocal} asks {@code
 * ZoneRules.getTransition(LocalDateTime)} directly (rather than leaning on {@code
 * ZonedDateTime.ofLocal}'s built-in gap/fold defaults, which cannot be told apart from an explicit
 * "earlier"/"later"/"reject") so each of the four {@code disambiguation} values resolves to exactly
 * the offset the spec calls for.
 *
 * <p><b>Calendar vs exact-time duration units.</b> {@link #add}/{@link #subtract} apply the
 * calendar-unit fields (years/months/weeks/days) against the receiver's local wall-clock date first,
 * re-resolving to an instant (always via "compatible" disambiguation, matching spec - add/subtract
 * take no disambiguation option), then apply the time-unit fields (hours..nanoseconds) as an exact
 * nanosecond delta directly to that intermediate instant. This means "add 1 day" and "add 24 hours"
 * can land on different results across a DST boundary, exactly as the spec requires.
 */
public final class TemporalZonedDateTimeBuiltins {
    private static final String CALENDAR_LIMITATION = "Duration operations involving years, months or weeks "
            + "require calendar-aware balancing (a relativeTo date), which is not implemented";

    public static final List<String> NAMES = List.of("with", "withCalendar", "withTimeZone", "withPlainDate",
            "withPlainTime", "add", "subtract", "until", "since", "round", "equals", "toInstant", "toPlainDate",
            "toPlainTime", "toPlainDateTime", "toPlainYearMonth", "toPlainMonthDay", "toString", "toJSON",
            "toLocaleString", "getISOFields", "getTimeZoneTransition", "valueOf");

    private static final String[] TIME_FIELD_NAMES = {"hour", "minute", "second", "millisecond", "microsecond",
            "nanosecond"};

    private static final BigInteger NANOS_PER_DAY = BigInteger.valueOf(86_400_000_000_000L);
    private static final BigInteger NANOS_PER_HOUR = BigInteger.valueOf(3_600_000_000_000L);
    private static final BigInteger NANOS_PER_MINUTE = BigInteger.valueOf(60_000_000_000L);
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger NANOS_PER_MILLI = BigInteger.valueOf(1_000_000L);
    private static final BigInteger NANOS_PER_MICRO = BigInteger.valueOf(1_000L);
    private static final BigInteger TWO = BigInteger.valueOf(2);

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

    private TemporalZonedDateTimeBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("ZonedDateTime", (_, args) -> {
            if (JsNativeFunction.currentNewTarget() == null) {
                throw new TypeErrorException("Constructor Temporal.ZonedDateTime requires 'new'");
            }
            return withNewTargetPrototype(construct(args, ops), ops);
        });
        ctor.setLength(2);
        ctor.setProperty("from",
                new JsNativeFunction("from", (_, args) -> toZonedDateTime(arg(args, 0), arg(args, 1), ops)));
        ctor.setProperty("compare", new JsNativeFunction("compare", (_, args) -> new JsNumber(
                compare(toZonedDateTime(arg(args, 0), ops), toZonedDateTime(arg(args, 1), ops)))));
        return ctor;
    }

    // OrdinaryCreateFromConstructor, mirroring TemporalInstantBuiltins' own helper.
    private static JsValue withNewTargetPrototype(JsTemporalZonedDateTime constructed, InterpreterOps ops) {
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

    private static JsTemporalZonedDateTime construct(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsBigInt bigInt)) {
            throw new TypeErrorException(
                    "Constructor Temporal.ZonedDateTime requires an epochNanoseconds BigInt argument");
        }
        final var timeZoneArg = arg(args, 1);
        if (timeZoneArg instanceof JsUndefined) {
            throw new TypeErrorException("Constructor Temporal.ZonedDateTime requires a timeZone argument");
        }
        final var timeZoneId = TemporalParser.parseTimeZoneIdentifier(JsCoercion.toStr(timeZoneArg, ops));
        final var calendarArg = arg(args, 2);
        if (!(calendarArg instanceof JsUndefined)) {
            requireIso8601(JsCoercion.toStr(calendarArg, ops));
        }
        return JsTemporalZonedDateTime.fromEpochNanoseconds(bigInt.getValue(), zoneOf(timeZoneId), timeZoneId);
    }

    private static void requireIso8601(String calendar) {
        if (!"iso8601".equals(calendar)) {
            throw new RangeErrorException(
                    "Only the \"iso8601\" calendar is supported by this engine, got: " + calendar);
        }
    }

    private static ZoneId zoneOf(String identifier) {
        if (!identifier.isEmpty() && (identifier.charAt(0) == '+' || identifier.charAt(0) == '-')) {
            return toZoneOffset(identifier);
        }
        try {
            return ZoneId.of(identifier);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid time zone identifier: " + identifier);
        }
    }

    private static ZoneOffset toZoneOffset(String offsetText) {
        try {
            if ("Z".equals(offsetText) || "z".equals(offsetText)) {
                return ZoneOffset.UTC;
            }
            var normalized = offsetText;
            final var dot = normalized.indexOf('.');
            if (dot >= 0) {
                normalized = normalized.substring(0, dot);
            }
            return ZoneOffset.of(normalized);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid UTC offset: " + offsetText);
        }
    }

    // Resolves local wall-clock fields to an instant per `disambiguation`. ZoneRules#getTransition
    // returns null for an unambiguous local time (including every local time in a fixed-offset zone,
    // which never has transitions) and the applicable gap/fold transition otherwise.
    private static ZonedDateTime resolveLocal(LocalDateTime local, ZoneId zone, Disambiguation disambiguation) {
        final var transition = zone.getRules().getTransition(local);
        if (transition == null) {
            return ZonedDateTime.of(local, zone);
        }
        if (transition.isGap()) {
            // Applying the pre-transition offset to a naive (nonexistent) local time lands the
            // resulting instant *after* the real transition point, which renders back (via the
            // post-transition offset) as the wall clock shifted forward by the gap's width - the
            // spec's "later"/"compatible" behavior (matching legacy Date). Applying the
            // post-transition offset instead lands before the transition and renders shifted
            // backward - "earlier". The two offsets are therefore swapped relative to their names.
            return switch (disambiguation) {
                case REJECT -> throw new RangeErrorException(
                        "Temporal.ZonedDateTime: local time falls in a time zone transition gap and "
                                + "disambiguation is 'reject'");
                case EARLIER -> local.toInstant(transition.getOffsetAfter()).atZone(zone);
                case LATER, COMPATIBLE -> local.toInstant(transition.getOffsetBefore()).atZone(zone);
            };
        }
        return switch (disambiguation) {
            case REJECT -> throw new RangeErrorException(
                    "Temporal.ZonedDateTime: local time is ambiguous (time zone transition fold) and "
                            + "disambiguation is 'reject'");
            case LATER -> local.toInstant(transition.getOffsetAfter()).atZone(zone);
            case EARLIER, COMPATIBLE -> local.toInstant(transition.getOffsetBefore()).atZone(zone);
        };
    }

    private static JsTemporalZonedDateTime resolveToZoned(Iso8601Fields date, IsoTimeFields time, ZoneId zone,
            String timeZoneId, Disambiguation disambiguation) {
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final LocalDateTime local;
        try {
            local = LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(), time.second(),
                    nanoOfSecond);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid Temporal.ZonedDateTime fields: " + e.getMessage());
        }
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(resolveLocal(local, zone, disambiguation), timeZoneId);
    }

    private static BigInteger epochNanosOf(ZonedDateTime zdt) {
        return BigInteger.valueOf(zdt.toEpochSecond()).multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(zdt.getNano()));
    }

    public static void installAccessors(JsObject proto) {
        installGetter(proto, "year", r -> new JsNumber(requireReceiver(r, "year").isoFieldsAtLocal().date().year()));
        installGetter(proto, "month", r -> new JsNumber(requireReceiver(r, "month").isoFieldsAtLocal().date().month()));
        installGetter(proto, "monthCode",
                r -> new JsString(monthCode(requireReceiver(r, "monthCode").isoFieldsAtLocal().date().month())));
        installGetter(proto, "day", r -> new JsNumber(requireReceiver(r, "day").isoFieldsAtLocal().date().day()));
        installGetter(proto, "hour", r -> new JsNumber(requireReceiver(r, "hour").isoFieldsAtLocal().time().hour()));
        installGetter(proto, "minute",
                r -> new JsNumber(requireReceiver(r, "minute").isoFieldsAtLocal().time().minute()));
        installGetter(proto, "second",
                r -> new JsNumber(requireReceiver(r, "second").isoFieldsAtLocal().time().second()));
        installGetter(proto, "millisecond",
                r -> new JsNumber(requireReceiver(r, "millisecond").isoFieldsAtLocal().time().millisecond()));
        installGetter(proto, "microsecond",
                r -> new JsNumber(requireReceiver(r, "microsecond").isoFieldsAtLocal().time().microsecond()));
        installGetter(proto, "nanosecond",
                r -> new JsNumber(requireReceiver(r, "nanosecond").isoFieldsAtLocal().time().nanosecond()));
        installGetter(proto, "dayOfWeek",
                r -> new JsNumber(IsoCalendar.dayOfWeek(requireReceiver(r, "dayOfWeek").isoFieldsAtLocal().date())));
        installGetter(proto, "dayOfYear",
                r -> new JsNumber(IsoCalendar.dayOfYear(requireReceiver(r, "dayOfYear").isoFieldsAtLocal().date())));
        installGetter(proto, "weekOfYear",
                r -> new JsNumber(IsoCalendar.weekOfYear(requireReceiver(r, "weekOfYear").isoFieldsAtLocal().date())));
        installGetter(proto, "yearOfWeek",
                r -> new JsNumber(IsoCalendar.yearOfWeek(requireReceiver(r, "yearOfWeek").isoFieldsAtLocal().date())));
        installGetter(proto, "daysInWeek", r -> {
            requireReceiver(r, "daysInWeek");
            return new JsNumber(7);
        });
        installGetter(proto, "daysInMonth", r -> {
            final var d = requireReceiver(r, "daysInMonth").isoFieldsAtLocal().date();
            return new JsNumber(IsoCalendar.daysInMonth(d.year(), d.month()));
        });
        installGetter(proto, "daysInYear", r -> {
            final var d = requireReceiver(r, "daysInYear").isoFieldsAtLocal().date();
            return new JsNumber(IsoCalendar.daysInYear(d.year()));
        });
        installGetter(proto, "monthsInYear", r -> {
            requireReceiver(r, "monthsInYear");
            return new JsNumber(12);
        });
        installGetter(proto, "inLeapYear", r -> {
            final var d = requireReceiver(r, "inLeapYear").isoFieldsAtLocal().date();
            return JsBoolean.of(IsoCalendar.isLeapYear(d.year()));
        });
        installGetter(proto, "calendarId", r -> {
            requireReceiver(r, "calendarId");
            return new JsString("iso8601");
        });
        installGetter(proto, "timeZoneId", r -> new JsString(requireReceiver(r, "timeZoneId").timeZoneId()));
        installGetter(proto, "epochMilliseconds",
                r -> new JsNumber(requireReceiver(r, "epochMilliseconds").epochMillisecondsLong()));
        installGetter(proto, "epochNanoseconds",
                r -> new JsBigInt(requireReceiver(r, "epochNanoseconds").epochNanoseconds()));
        installGetter(proto, "offsetNanoseconds", r -> new JsNumber(
                requireReceiver(r, "offsetNanoseconds").offset().getTotalSeconds() * 1_000_000_000.0));
        installGetter(proto, "offset",
                r -> new JsString(TemporalFormatter.formatOffset(requireReceiver(r, "offset").offset())));
        installGetter(proto, "hoursInDay", r -> new JsNumber(hoursInDay(requireReceiver(r, "hoursInDay"))));
    }

    private static double hoursInDay(JsTemporalZonedDateTime receiver) {
        final var zdt = receiver.toJavaZonedDateTime();
        final var startOfDay = zdt.toLocalDate().atStartOfDay(receiver.zone());
        final var startOfNextDay = zdt.toLocalDate().plusDays(1).atStartOfDay(receiver.zone());
        final var nanos = epochNanosOf(startOfNextDay).subtract(epochNanosOf(startOfDay));
        return nanos.doubleValue() / 3_600_000_000_000.0;
    }

    private static void installGetter(JsObject proto, String name, Function<JsValue, JsValue> impl) {
        final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> impl.apply(thisArg));
        getter.setLength(0);
        proto.defineAccessor(name, getter, null);
        proto.setFlags(name, new JsObject.PropertyFlags(true, false, true));
    }

    private static JsTemporalZonedDateTime requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsTemporalZonedDateTime zdt) {
            return zdt;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalZonedDateTime wrapped) {
            return wrapped;
        }
        throw new TypeErrorException(
                "Temporal.ZonedDateTime.prototype." + method + " called on an incompatible receiver");
    }

    public static JsValue getMethod(JsTemporalZonedDateTime receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "with" -> new JsNativeFunction("with", (_, args) -> with(receiver, arg(args, 0), arg(args, 1), ops));
            case "withCalendar" ->
                new JsNativeFunction("withCalendar", (_, args) -> withCalendar(receiver, arg(args, 0), ops));
            case "withTimeZone" ->
                new JsNativeFunction("withTimeZone", (_, args) -> withTimeZone(receiver, arg(args, 0), ops));
            case "withPlainDate" ->
                new JsNativeFunction("withPlainDate", (_, args) -> withPlainDate(receiver, arg(args, 0), ops));
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
            case "toInstant" -> new JsNativeFunction("toInstant", (_, _) -> receiver.toInstant());
            case "toPlainDate" -> new JsNativeFunction("toPlainDate", (_, _) -> toPlainDate(receiver));
            case "toPlainTime" -> new JsNativeFunction("toPlainTime", (_, _) -> toPlainTime(receiver));
            case "toPlainDateTime" -> new JsNativeFunction("toPlainDateTime", (_, _) -> toPlainDateTime(receiver));
            case "toPlainYearMonth" -> new JsNativeFunction("toPlainYearMonth", (_, _) -> toPlainYearMonth(receiver));
            case "toPlainMonthDay" -> new JsNativeFunction("toPlainMonthDay", (_, _) -> toPlainMonthDay(receiver));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> toStringMethod(receiver, arg(args, 0), ops));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(receiver.toString()));
            case "getISOFields" -> new JsNativeFunction("getISOFields", (_, _) -> getISOFields(receiver));
            case "getTimeZoneTransition" -> new JsNativeFunction("getTimeZoneTransition",
                    (_, args) -> getTimeZoneTransition(receiver, arg(args, 0), ops));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> {
                throw new TypeErrorException(
                        "Cannot convert a Temporal.ZonedDateTime to a primitive value with valueOf; use compare() or "
                                + "equals() instead");
            });
            default -> null;
        };
    }

    // ToTemporalZonedDateTime: accepts a real ZonedDateTime (copied), an ISO zoned date-time string,
    // or a fields-like object carrying a required `timeZone` property.
    private static JsTemporalZonedDateTime toZonedDateTime(JsValue item, InterpreterOps ops) {
        return toZonedDateTime(item, JsUndefined.getInstance(), ops);
    }

    private static JsTemporalZonedDateTime toZonedDateTime(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        final var overflow = readOverflowOption(optionsArg, ops);
        final var disambiguation = readDisambiguationOption(optionsArg, ops);
        if (item instanceof JsTemporalZonedDateTime zdt) {
            return new JsTemporalZonedDateTime(zdt.epochSecondsPart(), zdt.nanoAdjustment(), zdt.zone(),
                    zdt.timeZoneId());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalZonedDateTime wrapped) {
            return new JsTemporalZonedDateTime(wrapped.epochSecondsPart(), wrapped.nanoAdjustment(), wrapped.zone(),
                    wrapped.timeZoneId());
        }
        if (item instanceof JsString s) {
            return fromIsoString(s.getValue(), disambiguation);
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return zonedDateTimeFromFields(item, overflow, disambiguation, ops);
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.ZonedDateTime");
    }

    // A parsed string carrying both an explicit numeric UTC offset and an IANA annotation trusts the
    // offset for the exact instant (rather than consulting `disambiguation`, which only applies when
    // no offset is present) - a documented narrowing of the spec's full `offset` option (`use`/
    // `prefer`/`ignore`/`reject`), which is not otherwise implemented.
    private static JsTemporalZonedDateTime fromIsoString(String text, Disambiguation disambiguation) {
        final var parsed = TemporalParser.parseZonedDateTime(text);
        final var timeZoneId = TemporalParser.parseTimeZoneIdentifier(parsed.timeZoneId());
        final var zone = zoneOf(timeZoneId);
        final var date = parsed.date();
        final var time = parsed.time();
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final LocalDateTime local;
        try {
            local = LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(), time.second(),
                    nanoOfSecond);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid Temporal.ZonedDateTime string: " + e.getMessage());
        }
        final var instant = parsed.offset() != null
                ? local.toInstant(toZoneOffset(parsed.offset()))
                : resolveLocal(local, zone, disambiguation).toInstant();
        return new JsTemporalZonedDateTime(instant.getEpochSecond(), instant.getNano(), zone, timeZoneId);
    }

    private static JsTemporalZonedDateTime zonedDateTimeFromFields(JsValue obj, RegulateOverflow overflow,
            Disambiguation disambiguation, InterpreterOps ops) {
        final var timeZoneRaw = ops.getMember(obj, new JsString("timeZone"));
        if (timeZoneRaw instanceof JsUndefined) {
            throw new TypeErrorException("timeZone is required");
        }
        final var timeZoneId = TemporalParser.parseTimeZoneIdentifier(JsCoercion.toStr(timeZoneRaw, ops));
        final var zone = zoneOf(timeZoneId);
        final var year = requiredIntegerField(obj, "year", ops);
        final var month = resolveMonth(obj, ops);
        final var day = requiredIntegerField(obj, "day", ops);
        final var hour = fieldOrDefault(obj, "hour", 0, ops);
        final var minute = fieldOrDefault(obj, "minute", 0, ops);
        final var second = fieldOrDefault(obj, "second", 0, ops);
        final var millisecond = fieldOrDefault(obj, "millisecond", 0, ops);
        final var microsecond = fieldOrDefault(obj, "microsecond", 0, ops);
        final var nanosecond = fieldOrDefault(obj, "nanosecond", 0, ops);
        final var date = IsoCalendar.regulateDate(year, month, day, overflow);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return resolveToZoned(date, time, zone, timeZoneId, disambiguation);
    }

    private static int requiredIntegerField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required");
        }
        return toIntegerField(value, name, ops);
    }

    private static int resolveMonth(JsValue obj, InterpreterOps ops) {
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

    private static int resolveMonthWith(JsValue obj, int defaultMonth, InterpreterOps ops) {
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        if (!(monthCodeValue instanceof JsUndefined)) {
            return parseMonthCode(JsCoercion.toStr(monthCodeValue, ops));
        }
        return fieldOrDefault(obj, "month", defaultMonth, ops);
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

    private static String monthCode(int month) {
        return "M" + pad2(month);
    }

    private static String pad2(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static int fieldOrDefault(JsValue obj, String name, int defaultValue, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        return value instanceof JsUndefined ? defaultValue : toIntegerField(value, name, ops);
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

    private static Disambiguation readDisambiguationOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "disambiguation", ops);
        return value instanceof JsUndefined
                ? Disambiguation.COMPATIBLE
                : Disambiguation.parse(JsCoercion.toStr(value, ops));
    }

    // ZonedDateTime.until/since default largestUnit is "hour" (unlike PlainDateTime's "day"), since a
    // "day" is not a fixed-length unit once a time zone is attached.
    private static Unit readLargestUnitOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "largestUnit", ops);
        if (value instanceof JsUndefined) {
            return Unit.HOUR;
        }
        final var raw = JsCoercion.toStr(value, ops);
        return "auto".equals(raw) ? Unit.HOUR : Unit.parseTemporalUnit(raw);
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

    private static TemporalFormatter.TimeZoneNameOption readTimeZoneNameOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "timeZoneName", ops);
        return value instanceof JsUndefined
                ? TemporalFormatter.TimeZoneNameOption.AUTO
                : TemporalFormatter.TimeZoneNameOption.parse(JsCoercion.toStr(value, ops));
    }

    private static TemporalFormatter.OffsetOption readOffsetDisplayOption(JsValue optionsArg, InterpreterOps ops) {
        final var value = optionOrUndefined(optionsArg, "offset", ops);
        return value instanceof JsUndefined
                ? TemporalFormatter.OffsetOption.AUTO
                : TemporalFormatter.OffsetOption.parse(JsCoercion.toStr(value, ops));
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

    private static void validateRoundingIncrement(long increment, Unit unit) {
        if (unit == Unit.DAY) {
            if (increment != 1) {
                throw new RangeErrorException("Invalid roundingIncrement " + increment + " for unit day");
            }
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

    private static JsObject smallestUnitOptions(JsString value) {
        final var options = new JsObject();
        options.set("smallestUnit", value);
        return options;
    }

    private static JsValue with(JsTemporalZonedDateTime receiver, JsValue fieldsLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(fieldsLike) || fieldsLike instanceof JsTemporalZonedDateTime) {
            throw new TypeErrorException("Temporal.ZonedDateTime.prototype.with argument must be a plain object");
        }
        final var overflow = readOverflowOption(optionsArg, ops);
        final var disambiguation = readDisambiguationOption(optionsArg, ops);
        final var fields = receiver.isoFieldsAtLocal();
        final var year = fieldOrDefault(fieldsLike, "year", fields.date().year(), ops);
        final var month = resolveMonthWith(fieldsLike, fields.date().month(), ops);
        final var day = fieldOrDefault(fieldsLike, "day", fields.date().day(), ops);
        final var t = fields.time();
        final var hour = fieldOrDefault(fieldsLike, "hour", t.hour(), ops);
        final var minute = fieldOrDefault(fieldsLike, "minute", t.minute(), ops);
        final var second = fieldOrDefault(fieldsLike, "second", t.second(), ops);
        final var millisecond = fieldOrDefault(fieldsLike, "millisecond", t.millisecond(), ops);
        final var microsecond = fieldOrDefault(fieldsLike, "microsecond", t.microsecond(), ops);
        final var nanosecond = fieldOrDefault(fieldsLike, "nanosecond", t.nanosecond(), ops);
        final var newDate = IsoCalendar.regulateDate(year, month, day, overflow);
        final var newTime = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return resolveToZoned(newDate, newTime, receiver.zone(), receiver.timeZoneId(), disambiguation);
    }

    // withCalendar only validates the argument in ISO-only mode - it is otherwise an identity op.
    private static JsValue withCalendar(JsTemporalZonedDateTime receiver, JsValue calendarArg, InterpreterOps ops) {
        requireIso8601(JsCoercion.toStr(calendarArg, ops));
        return new JsTemporalZonedDateTime(receiver.epochSecondsPart(), receiver.nanoAdjustment(), receiver.zone(),
                receiver.timeZoneId());
    }

    private static JsValue withTimeZone(JsTemporalZonedDateTime receiver, JsValue timeZoneArg, InterpreterOps ops) {
        final var timeZoneId = TemporalParser.parseTimeZoneIdentifier(JsCoercion.toStr(timeZoneArg, ops));
        return new JsTemporalZonedDateTime(receiver.epochSecondsPart(), receiver.nanoAdjustment(), zoneOf(timeZoneId),
                timeZoneId);
    }

    private static JsValue withPlainDate(JsTemporalZonedDateTime receiver, JsValue dateArg, InterpreterOps ops) {
        final var date = toDateFields(dateArg, ops);
        final var time = receiver.isoFieldsAtLocal().time();
        return resolveToZoned(date, time, receiver.zone(), receiver.timeZoneId(), Disambiguation.COMPATIBLE);
    }

    private static Iso8601Fields toDateFields(JsValue value, InterpreterOps ops) {
        if (value instanceof JsTemporalPlainDate pd) {
            return pd.fields();
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalPlainDate wrapped) {
            return wrapped.fields();
        }
        if (value instanceof JsString s) {
            return TemporalParser.parseDate(s.getValue()).date();
        }
        if (InterpreterUtils.isObjectLike(value)) {
            final var year = requiredIntegerField(value, "year", ops);
            final var month = resolveMonth(value, ops);
            final var day = requiredIntegerField(value, "day", ops);
            return IsoCalendar.regulateDate(year, month, day, RegulateOverflow.CONSTRAIN);
        }
        throw new TypeErrorException("Temporal.ZonedDateTime.prototype.withPlainDate requires a date-like value");
    }

    private static JsValue withPlainTime(JsTemporalZonedDateTime receiver, JsValue timeArg, InterpreterOps ops) {
        final var time = toPlainTimeOrMidnight(timeArg, ops);
        final var date = receiver.isoFieldsAtLocal().date();
        return resolveToZoned(date, time, receiver.zone(), receiver.timeZoneId(), Disambiguation.COMPATIBLE);
    }

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
            case JsObject wrapper when wrapper.getPrimitive() instanceof JsTemporalPlainTime wrapped -> {
                return wrapped.getFields();
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
        throw new TypeErrorException("Temporal.ZonedDateTime.prototype.withPlainTime requires a time-like value");
    }

    private static IsoTimeFields timeFromObjectRequireAny(JsValue obj, InterpreterOps ops) {
        final var values = new int[6];
        var any = false;
        for (var i = 0; i < TIME_FIELD_NAMES.length; i++) {
            final var value = ops.getMember(obj, new JsString(TIME_FIELD_NAMES[i]));
            if (!(value instanceof JsUndefined)) {
                any = true;
                values[i] = toIntegerField(value, TIME_FIELD_NAMES[i], ops);
            }
        }
        if (!any) {
            throw new TypeErrorException("Invalid time-like object: no recognized properties");
        }
        return regulateTime(values[0], values[1], values[2], values[3], values[4], values[5],
                RegulateOverflow.CONSTRAIN);
    }

    // ToTemporalDuration duck-typing, matching every other Temporal type's arithmetic methods.
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
            final var fields = new DurationFields(durationFieldOrZero(value, "years", ops),
                    durationFieldOrZero(value, "months", ops), durationFieldOrZero(value, "weeks", ops),
                    durationFieldOrZero(value, "days", ops), durationFieldOrZero(value, "hours", ops),
                    durationFieldOrZero(value, "minutes", ops), durationFieldOrZero(value, "seconds", ops),
                    durationFieldOrZero(value, "milliseconds", ops), durationFieldOrZero(value, "microseconds", ops),
                    durationFieldOrZero(value, "nanoseconds", ops));
            DurationMath.sign(fields);
            return fields;
        }
        throw new TypeErrorException(
                "Expected a Temporal.Duration, an ISO 8601 duration string, or a duration-like object");
    }

    private static double durationFieldOrZero(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            return 0.0;
        }
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.floor(number)) {
            throw new RangeErrorException(name + " must be an integer");
        }
        return number;
    }

    private static DurationFields negate(DurationFields d) {
        return new DurationFields(-d.years(), -d.months(), -d.weeks(), -d.days(), -d.hours(), -d.minutes(),
                -d.seconds(), -d.milliseconds(), -d.microseconds(), -d.nanoseconds());
    }

    private static JsValue add(JsTemporalZonedDateTime receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        return addZonedDateTime(receiver, toDurationFields(durationLike, ops), readOverflowOption(optionsArg, ops));
    }

    private static JsValue subtract(JsTemporalZonedDateTime receiver, JsValue durationLike, JsValue optionsArg,
            InterpreterOps ops) {
        return addZonedDateTime(receiver, negate(toDurationFields(durationLike, ops)),
                readOverflowOption(optionsArg, ops));
    }

    private static JsValue addZonedDateTime(JsTemporalZonedDateTime receiver, DurationFields duration,
            RegulateOverflow overflow) {
        final var fields = receiver.isoFieldsAtLocal();
        final ZonedDateTime intermediate;
        if (duration.years() != 0 || duration.months() != 0 || duration.weeks() != 0 || duration.days() != 0) {
            final var newDate = IsoCalendar.addDate(fields.date(), duration.years(), duration.months(),
                    duration.weeks(), duration.days(), overflow);
            intermediate = resolveToZonedDateTime(newDate, fields.time(), receiver.zone());
        } else {
            intermediate = receiver.toJavaZonedDateTime();
        }
        final var deltaNanos = toDurationNanos(duration);
        final var resultNanos = epochNanosOf(intermediate).add(deltaNanos);
        return JsTemporalZonedDateTime.fromEpochNanoseconds(resultNanos, receiver.zone(), receiver.timeZoneId());
    }

    // add/subtract's calendar-unit phase always uses "compatible" disambiguation - the spec's
    // AddZonedDateTime/AddDateTime algorithms take no disambiguation option of their own.
    private static ZonedDateTime resolveToZonedDateTime(Iso8601Fields date, IsoTimeFields time, ZoneId zone) {
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final LocalDateTime local;
        try {
            local = LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(), time.second(),
                    nanoOfSecond);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid Temporal.ZonedDateTime fields: " + e.getMessage());
        }
        return resolveLocal(local, zone, Disambiguation.COMPATIBLE);
    }

    private static BigInteger toDurationNanos(DurationFields f) {
        return BigInteger.valueOf((long) f.hours()).multiply(NANOS_PER_HOUR)
                .add(BigInteger.valueOf((long) f.minutes()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf((long) f.seconds()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf((long) f.milliseconds()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf((long) f.microseconds()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf((long) f.nanoseconds()));
    }

    private static JsValue until(JsTemporalZonedDateTime receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, false, ops);
    }

    private static JsValue since(JsTemporalZonedDateTime receiver, JsValue otherArg, JsValue optionsArg,
            InterpreterOps ops) {
        return difference(receiver, otherArg, optionsArg, true, ops);
    }

    private static JsValue difference(JsTemporalZonedDateTime receiver, JsValue otherArg, JsValue optionsArg,
            boolean isSince, InterpreterOps ops) {
        final var other = toZonedDateTime(otherArg, ops);
        final var largestUnit = readLargestUnitOption(optionsArg, ops);
        final var smallestUnit = readSmallestUnitOption(optionsArg, ops);
        if (largestUnit.isLargerThan(Unit.DAY)) {
            throw new RangeErrorException(CALENDAR_LIMITATION);
        }
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        final var increment = readIncrementOption(optionsArg, ops);
        var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        if (isSince) {
            mode = negateRoundingMode(mode);
        }
        var fields = largestUnit == Unit.DAY
                ? dayAndTimeDifference(receiver, other)
                : DurationMath.balanceFromTotalNanoseconds(
                        other.epochNanoseconds().subtract(receiver.epochNanoseconds()), largestUnit);
        if (smallestUnit != Unit.NANOSECOND || increment != 1) {
            validateRoundingIncrement(increment, smallestUnit);
            fields = DurationMath.roundDuration(fields, smallestUnit, increment, mode, largestUnit);
        }
        if (isSince) {
            fields = negate(fields);
        }
        return new JsTemporalDuration(fields);
    }

    // A calendar-day count between two zoned date-times is not a fixed nanosecond multiple once DST
    // is involved: `startZdt.until(endZdt, DAYS)` (java.time's own zone-aware day count) gives the
    // whole-day part, then the remainder is whatever exact nanoseconds are left after re-adding that
    // many zone-aware days - capturing a 23/25-hour day correctly instead of assuming 24.
    private static DurationFields dayAndTimeDifference(JsTemporalZonedDateTime receiver,
            JsTemporalZonedDateTime other) {
        final var startZdt = receiver.toJavaZonedDateTime();
        final var endZdt = other.toJavaInstant().atZone(receiver.zone());
        final var days = startZdt.until(endZdt, ChronoUnit.DAYS);
        final var intermediate = startZdt.plusDays(days);
        final var remainderNanos = other.epochNanoseconds().subtract(epochNanosOf(intermediate));
        final var timeFields = DurationMath.balanceFromTotalNanoseconds(remainderNanos, Unit.HOUR);
        return new DurationFields(0, 0, 0, (double) days, timeFields.hours(), timeFields.minutes(),
                timeFields.seconds(), timeFields.milliseconds(), timeFields.microseconds(), timeFields.nanoseconds());
    }

    private static JsValue equalsMethod(JsTemporalZonedDateTime receiver, JsValue otherArg, InterpreterOps ops) {
        return JsBoolean.of(receiver.isEqualTo(toZonedDateTime(otherArg, ops)));
    }

    private static JsValue round(JsTemporalZonedDateTime receiver, JsValue roundToArg, InterpreterOps ops) {
        if (roundToArg == null || roundToArg instanceof JsUndefined) {
            throw new TypeErrorException("round() requires an options parameter");
        }
        final var options = roundToArg instanceof JsString unit ? smallestUnitOptions(unit) : roundToArg;
        if (!InterpreterUtils.isObjectLike(options)) {
            throw new TypeErrorException("options must be an object or a string");
        }
        final var smallestUnitValue = optionOrUndefined(options, "smallestUnit", ops);
        if (smallestUnitValue instanceof JsUndefined) {
            throw new RangeErrorException("smallestUnit is required");
        }
        final var smallestUnit = Unit.parseTemporalUnit(JsCoercion.toStr(smallestUnitValue, ops));
        if (smallestUnit.isLargerThan(Unit.DAY)) {
            throw new RangeErrorException(
                    "Invalid smallestUnit for Temporal.ZonedDateTime.prototype.round: " + smallestUnit.singular());
        }
        final var increment = readIncrementOption(options, ops);
        validateRoundingIncrement(increment, smallestUnit);
        final var mode = readRoundingModeOption(options, ops, RoundingMode.HALF_EXPAND);
        if (smallestUnit == Unit.DAY) {
            return roundToCalendarDay(receiver, mode);
        }
        final var incrementNanos = nanosPerUnit(smallestUnit).multiply(BigInteger.valueOf(increment));
        final var rounded = roundSignedNanoseconds(receiver.epochNanoseconds(), incrementNanos, mode);
        return JsTemporalZonedDateTime.fromEpochNanoseconds(rounded, receiver.zone(), receiver.timeZoneId());
    }

    // Rounding to a whole day is time-zone-aware: a local day is not fixed-length (86,400s) once DST
    // is involved (it can be 23/24/25 hours). This rounds the offset into the *current* local day
    // (measured against that day's own real length) rather than the raw epoch nanoseconds.
    private static JsValue roundToCalendarDay(JsTemporalZonedDateTime receiver, RoundingMode mode) {
        final var zdt = receiver.toJavaZonedDateTime();
        final var startOfDay = zdt.toLocalDate().atStartOfDay(receiver.zone());
        final var startOfNextDay = zdt.toLocalDate().plusDays(1).atStartOfDay(receiver.zone());
        final var dayLengthNanos = epochNanosOf(startOfNextDay).subtract(epochNanosOf(startOfDay));
        final var offsetIntoDayNanos = epochNanosOf(zdt).subtract(epochNanosOf(startOfDay));
        final var rounded = roundNonNegative(offsetIntoDayNanos, dayLengthNanos, mode);
        final var resultZdt = rounded.signum() == 0 ? startOfDay : startOfNextDay;
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(resultZdt, receiver.timeZoneId());
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
            default -> throw new RangeErrorException("Unsupported unit for Temporal.ZonedDateTime: " + unit.singular());
        };
    }

    // Non-negative-only rounding (an offset-into-a-day is always in [0, dayLength)) - structurally the
    // same eight-branch rule set as the other Temporal types' equivalents.
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
            case CEIL -> quotient.add(BigInteger.ONE);
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

    // Full-range signed rounding (an epoch nanosecond total can be negative, before 1970) - same rule
    // set as TemporalInstantBuiltins' equivalent.
    private static BigInteger roundSignedNanoseconds(BigInteger value, BigInteger increment, RoundingMode mode) {
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

    private static JsValue toPlainDate(JsTemporalZonedDateTime receiver) {
        return new JsTemporalPlainDate(receiver.isoFieldsAtLocal().date());
    }

    private static JsValue toPlainTime(JsTemporalZonedDateTime receiver) {
        return new JsTemporalPlainTime(receiver.isoFieldsAtLocal().time());
    }

    private static JsValue toPlainDateTime(JsTemporalZonedDateTime receiver) {
        final var fields = receiver.isoFieldsAtLocal();
        return new JsTemporalPlainDateTime(fields.date(), fields.time());
    }

    private static JsValue toPlainYearMonth(JsTemporalZonedDateTime receiver) {
        final var date = receiver.isoFieldsAtLocal().date();
        return new JsTemporalPlainYearMonth(new Iso8601Fields(date.year(), date.month(), 1));
    }

    private static JsValue toPlainMonthDay(JsTemporalZonedDateTime receiver) {
        final var date = receiver.isoFieldsAtLocal().date();
        return new JsTemporalPlainMonthDay(
                new Iso8601Fields(JsTemporalPlainMonthDay.DEFAULT_REFERENCE_ISO_YEAR, date.month(), date.day()));
    }

    private static JsValue getTimeZoneTransition(JsTemporalZonedDateTime receiver, JsValue directionArg,
            InterpreterOps ops) {
        final var direction = JsCoercion.toStr(directionArg, ops);
        final var rules = receiver.zone().getRules();
        final var transition = switch (direction) {
            case "next" -> rules.nextTransition(receiver.toJavaInstant());
            case "previous" -> rules.previousTransition(receiver.toJavaInstant());
            default -> throw new RangeErrorException("getTimeZoneTransition direction must be 'next' or 'previous'");
        };
        if (transition == null) {
            return JsNull.getInstance();
        }
        final var instant = transition.getInstant();
        return new JsTemporalZonedDateTime(instant.getEpochSecond(), instant.getNano(), receiver.zone(),
                receiver.timeZoneId());
    }

    private static JsValue getISOFields(JsTemporalZonedDateTime receiver) {
        final var fields = receiver.isoFieldsAtLocal();
        final var obj = new JsObject();
        obj.set("calendar", new JsString("iso8601"));
        obj.set("isoDay", new JsNumber(fields.date().day()));
        obj.set("isoMonth", new JsNumber(fields.date().month()));
        obj.set("isoYear", new JsNumber(fields.date().year()));
        final var t = fields.time();
        obj.set("isoHour", new JsNumber(t.hour()));
        obj.set("isoMinute", new JsNumber(t.minute()));
        obj.set("isoSecond", new JsNumber(t.second()));
        obj.set("isoMillisecond", new JsNumber(t.millisecond()));
        obj.set("isoMicrosecond", new JsNumber(t.microsecond()));
        obj.set("isoNanosecond", new JsNumber(t.nanosecond()));
        obj.set("offset", new JsString(TemporalFormatter.formatOffset(receiver.offset())));
        obj.set("timeZone", new JsString(receiver.timeZoneId()));
        return obj;
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

    private static long toNanosOfDayLong(IsoTimeFields t) {
        return t.hour() * 3_600_000_000_000L + t.minute() * 60_000_000_000L + t.second() * 1_000_000_000L
                + t.millisecond() * 1_000_000L + t.microsecond() * 1_000L + t.nanosecond();
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

    // Rounds the receiver's LOCAL wall-clock fields (carrying any overflow into the date), then
    // re-resolves to an instant via "compatible" disambiguation - used by toString()'s
    // smallestUnit/fractionalSecondDigits options, which round the displayed local time rather than
    // the exact epoch nanoseconds.
    private static JsTemporalZonedDateTime roundLocalByIncrementNanos(JsTemporalZonedDateTime receiver,
            BigInteger incrementNanos, RoundingMode mode) {
        final var fields = receiver.isoFieldsAtLocal();
        final var nanosOfDay = BigInteger.valueOf(toNanosOfDayLong(fields.time()));
        final var rounded = roundNonNegative(nanosOfDay, incrementNanos, mode);
        final var dayCarry = rounded.divide(NANOS_PER_DAY);
        final var remainder = rounded.subtract(dayCarry.multiply(NANOS_PER_DAY));
        final var newDate = dayCarry.signum() == 0
                ? fields.date()
                : IsoCalendar.addDate(fields.date(), 0, 0, 0, dayCarry.doubleValue(), RegulateOverflow.CONSTRAIN);
        final var newTime = fromNanosOfDay(remainder.longValueExact());
        return resolveToZoned(newDate, newTime, receiver.zone(), receiver.timeZoneId(), Disambiguation.COMPATIBLE);
    }

    private static JsTemporalZonedDateTime roundToLocalUnit(JsTemporalZonedDateTime receiver, Unit unit, long increment,
            RoundingMode mode) {
        return roundLocalByIncrementNanos(receiver, nanosPerUnit(unit).multiply(BigInteger.valueOf(increment)), mode);
    }

    private static JsTemporalZonedDateTime roundToLocalFractionalDigits(JsTemporalZonedDateTime receiver, int digits,
            RoundingMode mode) {
        return roundLocalByIncrementNanos(receiver, BigInteger.TEN.pow(9 - digits), mode);
    }

    private static JsValue toStringMethod(JsTemporalZonedDateTime receiver, JsValue optionsArg, InterpreterOps ops) {
        final var options = optionsArg instanceof JsObject opts ? opts : null;
        final var calendarName = readCalendarNameOption(options, ops);
        final var timeZoneOption = readTimeZoneNameOption(options, ops);
        final var offsetOption = readOffsetDisplayOption(options, ops);
        final var mode = readRoundingModeOption(options, ops, RoundingMode.TRUNC);
        var zoned = receiver;
        Integer fractionDigits = null;
        final var smallestUnitValue = optionOrUndefined(options, "smallestUnit", ops);
        if (!(smallestUnitValue instanceof JsUndefined)) {
            final var unitStr = JsCoercion.toStr(smallestUnitValue, ops);
            final var increment = readIncrementOption(options, ops);
            if ("minute".equals(unitStr)) {
                validateRoundingIncrement(increment, Unit.MINUTE);
                zoned = roundToLocalUnit(receiver, Unit.MINUTE, increment, mode);
                final var fields = zoned.isoFieldsAtLocal();
                final var offsetText = TemporalFormatter.formatOffset(zoned.offset());
                final var sb = new StringBuilder();
                sb.append(TemporalFormatter.formatDate(fields.date())).append('T').append(pad2(fields.time().hour()))
                        .append(':').append(pad2(fields.time().minute()));
                if (offsetOption != TemporalFormatter.OffsetOption.NEVER) {
                    sb.append(offsetText);
                }
                if (timeZoneOption != TemporalFormatter.TimeZoneNameOption.NEVER) {
                    sb.append('[');
                    if (timeZoneOption == TemporalFormatter.TimeZoneNameOption.CRITICAL) {
                        sb.append('!');
                    }
                    sb.append(zoned.timeZoneId()).append(']');
                }
                sb.append(TemporalFormatter.formatCalendarAnnotation(calendarName));
                return new JsString(sb.toString());
            }
            final var unit = Unit.parseTemporalUnit(unitStr);
            requireSecondOrSmallerUnit(unit);
            validateRoundingIncrement(increment, unit);
            zoned = roundToLocalUnit(receiver, unit, increment, mode);
            fractionDigits = digitsForUnit(unit);
        } else {
            final var fsdValue = optionOrUndefined(options, "fractionalSecondDigits", ops);
            if (!(fsdValue instanceof JsUndefined)
                    && !(fsdValue instanceof JsString s && "auto".equals(s.getValue()))) {
                final var digits = toIntegerField(fsdValue, "fractionalSecondDigits", ops);
                if (digits < 0 || digits > 9) {
                    throw new RangeErrorException("fractionalSecondDigits must be 0..9 or \"auto\"");
                }
                zoned = roundToLocalFractionalDigits(receiver, digits, mode);
                fractionDigits = digits;
            }
        }
        final var fields = zoned.isoFieldsAtLocal();
        final var offsetText = TemporalFormatter.formatOffset(zoned.offset());
        return new JsString(TemporalFormatter.formatZonedDateTime(fields.date(), fields.time(), fractionDigits,
                offsetText, zoned.timeZoneId(), timeZoneOption, offsetOption, calendarName));
    }

    private static int compare(JsTemporalZonedDateTime a, JsTemporalZonedDateTime b) {
        return Integer.signum(a.compareEpoch(b));
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}

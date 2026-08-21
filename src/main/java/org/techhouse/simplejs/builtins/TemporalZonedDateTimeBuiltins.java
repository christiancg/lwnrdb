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
import org.techhouse.simplejs.internal.temporal.RelativeDurationMath;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.TemporalCalendarIdentifier;
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
    public static final List<String> NAMES = List.of("with", "withCalendar", "withTimeZone", "withPlainDate",
            "withPlainTime", "add", "subtract", "until", "since", "round", "startOfDay", "equals", "toInstant",
            "toPlainDate", "toPlainTime", "toPlainDateTime", "toPlainYearMonth", "toPlainMonthDay", "toString",
            "toJSON", "toLocaleString", "getISOFields", "getTimeZoneTransition", "valueOf");

    private static final BigInteger NANOS_PER_DAY = BigInteger.valueOf(86_400_000_000_000L);
    private static final BigInteger NANOS_PER_HOUR = BigInteger.valueOf(3_600_000_000_000L);
    private static final BigInteger NANOS_PER_MINUTE = BigInteger.valueOf(60_000_000_000L);
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger NANOS_PER_MILLI = BigInteger.valueOf(1_000_000L);
    private static final BigInteger NANOS_PER_MICRO = BigInteger.valueOf(1_000L);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    enum Disambiguation {
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

    // GetTemporalOffsetOption: reconciles an explicit numeric UTC offset (a property-bag `offset`
    // field, or a string's inline offset) against what the time zone actually observes at that local
    // time - "use" always trusts the explicit offset for the exact time, "ignore" always discards it
    // (falling back to `disambiguation`), and "prefer"/"reject" use it only when it matches one of the
    // zone's valid offsets for that local time, otherwise falling back to disambiguation ("prefer") or
    // throwing ("reject").
    enum OffsetOption {
        USE, PREFER, IGNORE, REJECT;

        static OffsetOption parse(String value) {
            return switch (value) {
                case "use" -> USE;
                case "prefer" -> PREFER;
                case "ignore" -> IGNORE;
                case "reject" -> REJECT;
                default -> throw new RangeErrorException("Invalid offset option: " + value);
            };
        }
    }

    private TemporalZonedDateTimeBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("ZonedDateTime", (thisArg, args) -> {
            requireNewTarget(thisArg);
            return withNewTargetPrototype(construct(args, ops), ops);
        });
        ctor.setLength(2);
        final var from = new JsNativeFunction("from", (_, args) -> toZonedDateTime(arg(args, 0), arg(args, 1), ops));
        from.setLength(1);
        ctor.setProperty("from", from);
        final var compare = new JsNativeFunction("compare", (_,
                args) -> new JsNumber(compare(toZonedDateTime(arg(args, 0), ops), toZonedDateTime(arg(args, 1), ops))));
        compare.setLength(2);
        ctor.setProperty("compare", compare);
        return ctor;
    }

    // Unlike Map/Date (always reached as a bare global identifier, so a plain call's thisArg is
    // reliably undefined), Temporal.ZonedDateTime only ever exists as a member of the Temporal
    // namespace object - so a plain `Temporal.ZonedDateTime()` call's thisArg is that namespace
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
        throw new TypeErrorException("Constructor Temporal.ZonedDateTime requires 'new'");
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
        final var epochArg = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var epochNanoseconds = NumberBuiltins.toBigIntValue(epochArg, ops).getValue();
        final var timeZoneArg = arg(args, 1);
        if (timeZoneArg instanceof JsUndefined) {
            throw new TypeErrorException("Constructor Temporal.ZonedDateTime requires a timeZone argument");
        }
        if (!(timeZoneArg instanceof JsString timeZoneStr)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        final var timeZoneId = TemporalParser.parseTimeZoneIdentifier(timeZoneStr.getValue());
        final var calendarArg = arg(args, 2);
        if (!(calendarArg instanceof JsUndefined)) {
            requireCalendarString(calendarArg);
        }
        return JsTemporalZonedDateTime.fromEpochNanoseconds(epochNanoseconds, zoneOf(timeZoneId), timeZoneId);
    }

    // Constructor / withCalendar accept only a bare calendar identifier; a non-string value is a
    // TypeError, not a RangeError.
    private static void requireCalendarString(JsValue calendarArg) {
        if (!(calendarArg instanceof JsString s)) {
            throw new TypeErrorException("calendar must be a string");
        }
        TemporalCalendarIdentifier.canonicalizeBare(s.getValue());
    }

    static ZoneId zoneOf(String identifier) {
        if (!identifier.isEmpty() && (identifier.charAt(0) == '+' || identifier.charAt(0) == '-')) {
            return toZoneOffset(identifier);
        }
        // "UTC" is matched ASCII-case-insensitively (java.time's ZoneId.of is exact-case), matching
        // TemporalCalendarIdentifier's own manual fold rather than a locale-sensitive one.
        if (TemporalCalendarIdentifier.asciiEqualsIgnoreCase(identifier, "utc")) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(identifier);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid time zone identifier: " + identifier);
        }
    }

    private static ZoneOffset toZoneOffset(String offsetText) {
        if ("Z".equals(offsetText) || "z".equals(offsetText)) {
            return ZoneOffset.UTC;
        }
        // The Temporal UTC-offset grammar is stricter than java.time's own lenient ZoneOffset.of parser
        // (e.g. it rejects "+0" - a 2-digit hour is mandatory - and a fraction with no preceding
        // seconds component, like "+00:00.0") - validated (and required to consume the whole string)
        // before ZoneOffset.of ever sees a stripped-down, always-well-formed remainder.
        TemporalParser.parseUtcOffsetString(offsetText);
        try {
            var normalized = offsetText;
            final var dot = normalized.indexOf('.');
            final var comma = normalized.indexOf(',');
            final var fractionStart = dot < 0 ? comma : (comma < 0 ? dot : Math.min(dot, comma));
            if (fractionStart >= 0) {
                normalized = normalized.substring(0, fractionStart);
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

    // ISODateWithinLimits already guarantees the date part is representable; the calendar-anchored
    // wall-clock range is narrower by exactly one instant at the very bottom - midnight on the
    // minimum ISO date is the sole date/time-of-day combination that falls outside the true
    // +-8.64e21ns instant envelope once treated as a naive (zero-offset) nanosecond count. Mirrors
    // TemporalPlainDateBuiltins.MIN_PLAIN_DATE/toPlainDateTime - see
    // PlainDate/prototype/toZonedDateTime/throws-if-combined-date-time-outside-valid-iso-range.js.
    private static final Iso8601Fields MIN_ISO_DATE = new Iso8601Fields(-271821, 4, 19);

    private static boolean isMidnight(IsoTimeFields time) {
        return time.hour() == 0 && time.minute() == 0 && time.second() == 0 && time.millisecond() == 0
                && time.microsecond() == 0 && time.nanosecond() == 0;
    }

    static JsTemporalZonedDateTime resolveToZoned(Iso8601Fields date, IsoTimeFields time, ZoneId zone,
            String timeZoneId, Disambiguation disambiguation) {
        return resolveToZonedWithOffset(date, time, zone, timeZoneId, null, disambiguation, OffsetOption.IGNORE);
    }

    // InterpretISODateTimeOffset: `offsetText` null means no explicit offset was supplied at all
    // (offsetBehaviour "wall" - disambiguation alone resolves the local time); otherwise it is
    // reconciled against the zone per `offsetOption` (see the OffsetOption enum's own doc comment).
    private static ZonedDateTime interpretOffset(LocalDateTime local, ZoneId zone, String offsetText,
            Disambiguation disambiguation, OffsetOption offsetOption) {
        if (offsetText == null || offsetOption == OffsetOption.IGNORE) {
            return resolveLocal(local, zone, disambiguation);
        }
        final var explicitOffset = toZoneOffset(offsetText);
        if (offsetOption == OffsetOption.USE) {
            return local.atZone(explicitOffset).withZoneSameInstant(zone);
        }
        if (zone.getRules().getValidOffsets(local).contains(explicitOffset)) {
            return local.atZone(explicitOffset).withZoneSameInstant(zone);
        }
        if (offsetOption == OffsetOption.REJECT) {
            throw new RangeErrorException(
                    "offset " + offsetText + " does not match time zone " + zone + " at " + local);
        }
        return resolveLocal(local, zone, disambiguation);
    }

    static JsTemporalZonedDateTime resolveToZonedWithOffset(Iso8601Fields date, IsoTimeFields time, ZoneId zone,
            String timeZoneId, String offsetText, Disambiguation disambiguation, OffsetOption offsetOption) {
        if (date.equals(MIN_ISO_DATE) && isMidnight(time)) {
            throw new RangeErrorException("date-time value is outside the representable range");
        }
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final LocalDateTime local;
        try {
            local = LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(), time.second(),
                    nanoOfSecond);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid Temporal.ZonedDateTime fields: " + e.getMessage());
        }
        final var resolved = interpretOffset(local, zone, offsetText, disambiguation, offsetOption);
        // The wall-clock/naive check above doesn't catch every case - GetStartOfDay's resulting
        // instant can still fall outside the Instant range once the zone's offset is applied (e.g. a
        // date within the naive range combined with an offset that pushes the exact instant past the
        // boundary) - see ZonedDateTime/from/argument-string-start-of-day-not-valid-epoch-nanoseconds.js.
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(resolved));
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(resolved, timeZoneId);
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
        installGetter(proto, "era", r -> {
            requireReceiver(r, "era");
            return JsUndefined.getInstance();
        });
        installGetter(proto, "eraYear", r -> {
            requireReceiver(r, "eraYear");
            return JsUndefined.getInstance();
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

    // GetStartOfDay is computed (and range-validated) for both today and tomorrow, even though only
    // their difference is observable - a value near either edge of the representable range can have a
    // valid receiver but an out-of-range start-of-day for one of the two boundary days - see
    // hoursInDay/get-start-of-day-throws.js and hoursInDay/next-day-out-of-range.js.
    private static double hoursInDay(JsTemporalZonedDateTime receiver) {
        final var zdt = receiver.toJavaZonedDateTime();
        final var startOfDay = zdt.toLocalDate().atStartOfDay(receiver.zone());
        final var startOfNextDay = zdt.toLocalDate().plusDays(1).atStartOfDay(receiver.zone());
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(startOfDay));
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(startOfNextDay));
        final var nanos = epochNanosOf(startOfNextDay).subtract(epochNanosOf(startOfDay));
        return nanos.doubleValue() / 3_600_000_000_000.0;
    }

    // GetStartOfDay: the receiver's own local calendar day at midnight, in the same time zone -
    // java.time's own atStartOfDay already resolves a nonexistent midnight (a DST spring-forward
    // whose transition starts before 00:00) to the first valid instant of that day. The result must
    // still fall within Instant's own range - see startOfDay/throws-if-epoch-nanoseconds-outside-
    // valid-limits.js.
    private static JsValue startOfDay(JsTemporalZonedDateTime receiver) {
        final var localDate = receiver.toJavaZonedDateTime().toLocalDate();
        final var startOfDay = localDate.atStartOfDay(receiver.zone());
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(startOfDay));
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(startOfDay, receiver.timeZoneId());
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
                new JsNativeFunction("withCalendar", (_, args) -> withCalendar(receiver, arg(args, 0)));
            case "withTimeZone" ->
                new JsNativeFunction("withTimeZone", (_, args) -> withTimeZone(receiver, arg(args, 0)));
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
            case "startOfDay" -> new JsNativeFunction("startOfDay", (_, _) -> startOfDay(receiver));
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

    // Item-type dispatch happens BEFORE any options are touched: a primitive item (not a Temporal
    // instance/string/plain object) is rejected with no options access at all (see
    // from/observable-get-overflow-argument-primitive.js), a malformed string throws before options
    // are read (see from/observable-get-overflow-argument-string-invalid.js), and a fields-like object
    // reads every one of its own fields to completion before any option is touched (see
    // from/order-of-operations.js).
    private static JsTemporalZonedDateTime toZonedDateTime(JsValue item, JsValue optionsArg, InterpreterOps ops) {
        if (item instanceof JsTemporalZonedDateTime zdt) {
            readCloneOptions(optionsArg, ops);
            return new JsTemporalZonedDateTime(zdt.epochSecondsPart(), zdt.nanoAdjustment(), zdt.zone(),
                    zdt.timeZoneId());
        }
        if (item instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalZonedDateTime wrapped) {
            readCloneOptions(optionsArg, ops);
            return new JsTemporalZonedDateTime(wrapped.epochSecondsPart(), wrapped.nanoAdjustment(), wrapped.zone(),
                    wrapped.timeZoneId());
        }
        if (item instanceof JsString s) {
            return fromIsoString(s.getValue(), optionsArg, ops);
        }
        if (InterpreterUtils.isObjectLike(item)) {
            return zonedDateTimeFromFields(item, optionsArg, ops);
        }
        throw new TypeErrorException("Cannot convert value to a Temporal.ZonedDateTime");
    }

    // Cloning a ZonedDateTime instance still validates (and discards) every option, in the same
    // (disambiguation, offset, overflow) order the fields/string paths use - see
    // from/order-of-operations.js's "order of operations when cloning a ZonedDateTime instance" and
    // from/offset-wrong-type.js.
    private static void readCloneOptions(JsValue optionsArg, InterpreterOps ops) {
        readDisambiguationOption(optionsArg, ops);
        readOffsetOption(optionsArg, ops, OffsetOption.REJECT);
        readOverflowOption(optionsArg, ops);
    }

    // ParseISODateTime determines the string's offsetBehaviour: a bare 'Z' designator always trusts
    // the zero offset for the exact instant, ignoring `offset` entirely (see
    // from/offset-overrides-critical-flag.js "despite critical flag" - the annotation's criticality
    // never changes offset semantics); an explicit numeric offset is reconciled against the bracketed
    // zone per the `offset` option (see from/zoneddatetime-string.js); and no offset at all falls back
    // to `disambiguation` alone. Parsing (which can throw RangeError) always happens before any option
    // is read - see from/observable-get-overflow-argument-string-invalid.js and
    // from/options-wrong-type.js's "Invalid string string processed before throwing TypeError".
    private static JsTemporalZonedDateTime fromIsoString(String text, JsValue optionsArg, InterpreterOps ops) {
        final var parsed = TemporalParser.parseZonedDateTime(text);
        if (parsed.calendar() != null) {
            TemporalCalendarIdentifier.canonicalizeBare(parsed.calendar());
        }
        final var timeZoneId = TemporalParser.parseTimeZoneIdentifier(parsed.timeZoneId());
        final var zone = zoneOf(timeZoneId);
        final var date = parsed.date();
        final var time = parsed.time() != null ? parsed.time() : new IsoTimeFields(0, 0, 0, 0, 0, 0);
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        final LocalDateTime local;
        try {
            local = LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(), time.second(),
                    nanoOfSecond);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid Temporal.ZonedDateTime string: " + e.getMessage());
        }
        final var disambiguation = readDisambiguationOption(optionsArg, ops);
        final var offsetOption = readOffsetOption(optionsArg, ops, OffsetOption.REJECT);
        readOverflowOption(optionsArg, ops);
        final var resolved = "Z".equals(parsed.offset())
                ? local.atZone(ZoneOffset.UTC).withZoneSameInstant(zone)
                : interpretOffset(local, zone, parsed.offset(), disambiguation, offsetOption);
        // GetStartOfDay's (or the explicit-offset's) resulting instant can fall outside the Instant
        // range even though the wall-clock date/time was itself in range - see
        // ZonedDateTime/from/argument-string-start-of-day-not-valid-epoch-nanoseconds.js.
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(resolved));
        return JsTemporalZonedDateTime.fromJavaZonedDateTime(resolved, timeZoneId);
    }

    // ToTemporalZonedDateTime (fields case): PrepareTemporalFields reads every recognized field in
    // alphabetical order (calendar, day, hour, microsecond, millisecond, minute, month, monthCode,
    // nanosecond, offset, second, timeZone, year) regardless of presence, then
    // GetTemporalDisambiguationOption/GetTemporalOffsetOption/GetTemporalOverflowOption run last, in
    // that order - see from/order-of-operations.js. day/month use ToPositiveIntegerWithTruncation
    // (reject <= 0 unconditionally, independent of overflow - see from/negative-month-or-day.js);
    // monthCode's *syntax* is validated the moment it is read (RangeError immediately, before any
    // later field is even read - see from/monthcode-invalid.js's "L99M" case) while its *numeric
    // suitability* (range/leap-suffix) and any month/monthCode conflict are resolved only after every
    // field (including year) and every option have been read - see
    // from/calendarresolvefields-error-ordering.js and from/monthcode-invalid.js's "M99L" case. The
    // `offset` field's *syntax* is likewise validated immediately (see from/offset-string-invalid.js),
    // while whether it *matches* the time zone is resolved at the very end, in resolveToZonedWithOffset.
    private static JsTemporalZonedDateTime zonedDateTimeFromFields(JsValue obj, JsValue optionsArg,
            InterpreterOps ops) {
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
        final var offsetValue = ops.getMember(obj, new JsString("offset"));
        final var offsetText = offsetValue instanceof JsUndefined ? null : requireOffsetFieldSyntax(offsetValue, ops);
        final var second = fieldOrDefault(obj, "second", 0, ops);
        final var timeZoneId = requireTimeZoneField(obj, ops);
        final var year = requiredIntegerField(obj, "year", ops);
        final var disambiguation = readDisambiguationOption(optionsArg, ops);
        final var offsetOption = readOffsetOption(optionsArg, ops, OffsetOption.REJECT);
        final var overflow = readOverflowOption(optionsArg, ops);
        final var zone = zoneOf(timeZoneId);
        final var resolvedMonth = resolveMonthValue(month, monthCode);
        final var date = IsoCalendar.regulateDate(year, resolvedMonth, day, overflow);
        final var time = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return resolveToZonedWithOffset(date, time, zone, timeZoneId, offsetText, disambiguation, offsetOption);
    }

    private static int requiredIntegerField(JsValue obj, String name, InterpreterOps ops) {
        final var value = ops.getMember(obj, new JsString(name));
        if (value instanceof JsUndefined) {
            throw new TypeErrorException(name + " is required");
        }
        return toIntegerField(value, name, ops);
    }

    // ToTemporalTimeZoneIdentifier: a real Temporal.ZonedDateTime (or a subclass wrapper around one)
    // supplies its own time zone identifier directly (a fast path, no further Get calls) - see
    // from/argument-propertybag-timezone-object.js; otherwise the value must be a string.
    private static String requireTimeZoneField(JsValue obj, InterpreterOps ops) {
        final var timeZoneRaw = ops.getMember(obj, new JsString("timeZone"));
        if (timeZoneRaw instanceof JsUndefined) {
            throw new TypeErrorException("timeZone is required");
        }
        if (timeZoneRaw instanceof JsTemporalZonedDateTime zdt) {
            return zdt.timeZoneId();
        }
        if (timeZoneRaw instanceof JsObject wrapper
                && wrapper.getPrimitive() instanceof JsTemporalZonedDateTime wrapped) {
            return wrapped.timeZoneId();
        }
        if (!(timeZoneRaw instanceof JsString timeZoneStr)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        return TemporalParser.parseTimeZoneIdentifierFlexible(timeZoneStr.getValue());
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

    // Simple month resolution for a plain date-like argument (withPlainDate) - no PrepareTemporalFields
    // observable-order contract applies here, just the month/monthCode reconciliation itself.
    private static int resolveMonthSimple(JsValue obj, InterpreterOps ops) {
        final var monthValue = ops.getMember(obj, new JsString("month"));
        final var monthCodeValue = ops.getMember(obj, new JsString("monthCode"));
        final Integer month = monthValue instanceof JsUndefined ? null : toIntegerField(monthValue, "month", ops);
        final String monthCode = monthCodeValue instanceof JsUndefined
                ? null
                : requireMonthCodeSyntax(monthCodeValue, ops);
        if (month == null && monthCode == null) {
            throw new TypeErrorException("month or monthCode is required");
        }
        return resolveMonthValue(month, monthCode);
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

    private static OffsetOption readOffsetOption(JsValue optionsArg, InterpreterOps ops, OffsetOption fallback) {
        final var value = optionOrUndefined(optionsArg, "offset", ops);
        return value instanceof JsUndefined ? fallback : OffsetOption.parse(JsCoercion.toStr(value, ops));
    }

    // PrepareTemporalFields's `offset` field type is ToPrimitiveAndRequireString (like monthCode) - a
    // non-string primitive is rejected with TypeError before its syntax is even checked; a
    // syntactically malformed offset (missing sign, wrong width, etc.) is a RangeError, validated
    // immediately when the field is read - whether it *matches* the time zone is a separate, later
    // concern (see resolveToZonedWithOffset/interpretOffset).
    private static String requireOffsetFieldSyntax(JsValue value, InterpreterOps ops) {
        final var primitive = JsCoercion.toPrimitive(value, "string", ops);
        if (!(primitive instanceof JsString s)) {
            throw new TypeErrorException("offset must be a string");
        }
        final var text = s.getValue();
        toZoneOffset(text);
        return text;
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
        validateRoundingIncrementForDuration(increment, unit);
    }

    // until()/since() round a computed Duration rather than a wall-clock reading, so "day" has no
    // natural cycle length to divide evenly into and any increment in [1, 1e9] is valid; time units
    // keep the same bounded-divisor rule as round()/toString().
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
    // falls through to Object.prototype.
    private static JsObject smallestUnitOptions(JsString value) {
        final var options = new JsObject();
        options.setProto(null);
        options.set("smallestUnit", value);
        return options;
    }

    // RejectObjectWithCalendarOrTimeZone runs first (calendar/timeZone properties are disallowed on a
    // with() argument, and every built-in Temporal type - not just the calendar-bearing ones - is
    // rejected outright before any property is even read, so a poisoned calendar/timeZone getter on a
    // genuine instance is never invoked - see with/throws-on-temporal-object-with-calendar.js), then
    // every field is read in alphabetical order regardless of presence (day/month use
    // ToPositiveIntegerWithTruncation exactly like from(), so with({day: -1}, badOptions) still
    // RangeErrors before the options argument's own type is ever checked - see
    // with/options-wrong-type.js), then GetTemporalDisambiguationOption/GetTemporalOffsetOption/
    // GetTemporalOverflowOption run last - see with/order-of-operations.js. Unlike from(), the
    // `offset` option's own fallback is "prefer", not "reject" - see with/offset-wrong-type.js.
    private static JsValue with(JsTemporalZonedDateTime receiver, JsValue fieldsLike, JsValue optionsArg,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(fieldsLike) || isAnyTemporalValue(fieldsLike)) {
            throw new TypeErrorException("Temporal.ZonedDateTime.prototype.with argument must be a plain object");
        }
        rejectCalendarOrTimeZoneField(fieldsLike, ops);
        final var fields = receiver.isoFieldsAtLocal();
        final var t = fields.time();
        var any = false;
        final var dayValue = ops.getMember(fieldsLike, new JsString("day"));
        any |= !(dayValue instanceof JsUndefined);
        final var day = dayValue instanceof JsUndefined
                ? fields.date().day()
                : toPositiveIntegerField(dayValue, "day", ops);
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
        final var offsetValue = ops.getMember(fieldsLike, new JsString("offset"));
        any |= !(offsetValue instanceof JsUndefined);
        final var offsetText = offsetValue instanceof JsUndefined ? null : requireOffsetFieldSyntax(offsetValue, ops);
        final var secondValue = ops.getMember(fieldsLike, new JsString("second"));
        any |= !(secondValue instanceof JsUndefined);
        final var second = secondValue instanceof JsUndefined ? t.second() : toIntegerField(secondValue, "second", ops);
        final var yearValue = ops.getMember(fieldsLike, new JsString("year"));
        any |= !(yearValue instanceof JsUndefined);
        final var year = yearValue instanceof JsUndefined
                ? fields.date().year()
                : toIntegerField(yearValue, "year", ops);
        if (!any) {
            throw new TypeErrorException("with() argument must contain at least one recognized property");
        }
        final var disambiguation = readDisambiguationOption(optionsArg, ops);
        final var offsetOption = readOffsetOption(optionsArg, ops, OffsetOption.PREFER);
        final var overflow = readOverflowOption(optionsArg, ops);
        final var resolvedMonth = resolveMonthValue(month, monthCode, fields.date().month());
        final var newDate = IsoCalendar.regulateDate(year, resolvedMonth, day, overflow);
        final var newTime = regulateTime(hour, minute, second, millisecond, microsecond, nanosecond, overflow);
        return resolveToZonedWithOffset(newDate, newTime, receiver.zone(), receiver.timeZoneId(), offsetText,
                disambiguation, offsetOption);
    }

    private static void rejectCalendarOrTimeZoneField(JsValue fieldsLike, InterpreterOps ops) {
        if (!(ops.getMember(fieldsLike, new JsString("calendar")) instanceof JsUndefined)) {
            throw new TypeErrorException(
                    "Temporal.ZonedDateTime.prototype.with argument must not have a calendar property");
        }
        if (!(ops.getMember(fieldsLike, new JsString("timeZone")) instanceof JsUndefined)) {
            throw new TypeErrorException(
                    "Temporal.ZonedDateTime.prototype.with argument must not have a timeZone property");
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
    // calendar-bearing ones - a plain Temporal.PlainTime is equally not a valid partial-fields object).
    private static boolean isAnyTemporalValue(JsValue value) {
        return isTemporalWithCalendar(value) || value instanceof JsTemporalPlainTime;
    }

    // withCalendar accepts the broader CalendarString grammar (a full ISO date/date-time/time string,
    // extracting or defaulting its u-ca annotation) or any of the five Temporal types carrying an ISO
    // date, read via a fast path that never touches the argument's own calendar/timeZone properties -
    // it is otherwise an identity op in ISO-only mode.
    private static JsValue withCalendar(JsTemporalZonedDateTime receiver, JsValue calendarArg) {
        if (!isTemporalWithCalendar(calendarArg)) {
            if (!(calendarArg instanceof JsString s)) {
                throw new TypeErrorException("calendar must be a string");
            }
            TemporalCalendarIdentifier.canonicalizeFlexible(s.getValue());
        }
        return new JsTemporalZonedDateTime(receiver.epochSecondsPart(), receiver.nanoAdjustment(), receiver.zone(),
                receiver.timeZoneId());
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

    private static JsValue withTimeZone(JsTemporalZonedDateTime receiver, JsValue timeZoneArg) {
        if (!(timeZoneArg instanceof JsString s)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        final var timeZoneId = TemporalParser.parseTimeZoneIdentifierFlexible(s.getValue());
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
            final var month = resolveMonthSimple(value, ops);
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
        // GetDifferenceSettings reads all four options in alphabetical order (largestUnit,
        // roundingIncrement, roundingMode, smallestUnit) before any algorithmic validation runs - see
        // since/order-of-operations.js and since/options-read-before-algorithmic-validation.js.
        // largestUnit's raw string is captured first (its cast to a real Unit, and the "auto" default,
        // both operate on that captured Java string with no further observable effect) and resolved
        // against smallestUnit only once smallestUnit itself has been read.
        final var largestUnitValue = optionOrUndefined(optionsArg, "largestUnit", ops);
        final var largestUnitRaw = largestUnitValue instanceof JsUndefined
                ? null
                : JsCoercion.toStr(largestUnitValue, ops);
        final var increment = readIncrementOption(optionsArg, ops);
        var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        final var smallestUnit = readSmallestUnitOption(optionsArg, ops);
        final var largestUnitDefault = smallestUnit.isLargerThan(Unit.HOUR) ? smallestUnit : Unit.HOUR;
        final var largestUnit = largestUnitRaw == null || "auto".equals(largestUnitRaw)
                ? largestUnitDefault
                : Unit.parseTemporalUnit(largestUnitRaw);
        if (smallestUnit.ordinal() < largestUnit.ordinal()) {
            throw new RangeErrorException("smallestUnit must not be larger than largestUnit");
        }
        if (isSince) {
            mode = negateRoundingMode(mode);
        }
        DurationFields fields;
        if (largestUnit.isLargerThan(Unit.DAY)) {
            // Calendar-unit differences between zoned date-times are computed on the receiver's local
            // wall-clock date+time (DifferenceZonedDateTime); only the exact rounding decision below
            // "day" needs the real instant/zone, via RelativeDurationMath's zoned Anchor.
            final var anchorFields = receiver.isoFieldsAtLocal();
            final var anchor = RelativeDurationMath.Anchor.zoned(anchorFields.date(), anchorFields.time(),
                    receiver.zone());
            final var otherLocal = other.isoFieldsAtLocal();
            fields = smallestUnit != Unit.NANOSECOND || increment != 1
                    ? RelativeDurationMath.roundedDifference(anchor, otherLocal.date(), otherLocal.time(), largestUnit,
                            smallestUnit, increment, mode)
                    : DurationMath.differenceCalendar(anchor.date(), anchor.time(), otherLocal.date(),
                            otherLocal.time(), largestUnit);
        } else {
            fields = largestUnit == Unit.DAY
                    ? dayAndTimeDifference(receiver, other)
                    : DurationMath.balanceFromTotalNanoseconds(
                            other.epochNanoseconds().subtract(receiver.epochNanoseconds()), largestUnit);
            if (smallestUnit != Unit.NANOSECOND || increment != 1) {
                validateRoundingIncrementForDuration(increment, smallestUnit);
                if (smallestUnit == Unit.DAY) {
                    validateCalendarUnitRoundingBound(receiver, other, increment, isSince);
                }
                fields = DurationMath.roundDuration(fields, smallestUnit, increment, mode, largestUnit);
            }
        }
        if (isSince) {
            fields = negate(fields);
        }
        return new JsTemporalDuration(fields);
    }

    // NudgeToCalendarUnit unconditionally computes the day-unit rounding's candidate "end" boundary
    // (the receiver's local date plus one increment of days, in the FINAL reported duration's own
    // direction) even when the actual remainder needs no rounding at all - so an increment that pushes
    // that boundary outside the representable instant range throws regardless of the chosen
    // roundingMode - see since/roundingincrement-addition-out-of-range.js. The direction is the sign
    // of the value `since`/`until` actually report (receiver-other for since, other-receiver for
    // until), not the internal pre-negation until-direction `fields` is computed in. The boundary is
    // checked at MIDNIGHT on the resulting date, not at the receiver's own time-of-day - the
    // Instant-range envelope has room for any time-of-day on every representable calendar day (that is
    // the whole point of PlainDate's extra day of headroom on the negative side), so carrying the
    // receiver's non-midnight time forward would reject a boundary date that is otherwise fine.
    private static void validateCalendarUnitRoundingBound(JsTemporalZonedDateTime receiver,
            JsTemporalZonedDateTime other, long increment, boolean isSince) {
        final var rawSign = other.epochNanoseconds().compareTo(receiver.epochNanoseconds());
        if (rawSign == 0) {
            return;
        }
        final var finalSign = isSince ? -Integer.signum(rawSign) : Integer.signum(rawSign);
        final var anchorDate = receiver.isoFieldsAtLocal().date();
        final var endDate = IsoCalendar.addDate(anchorDate, 0, 0, 0, (double) finalSign * increment,
                RegulateOverflow.CONSTRAIN);
        final var midnight = new IsoTimeFields(0, 0, 0, 0, 0, 0);
        final var endZdt = resolveToZonedDateTime(endDate, midnight, receiver.zone());
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(endZdt));
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
                    "Invalid smallestUnit for Temporal.ZonedDateTime.prototype.round: " + smallestUnit.singular());
        }
        validateRoundingIncrement(increment, smallestUnit);
        if (smallestUnit == Unit.DAY) {
            return roundToCalendarDay(receiver, mode);
        }
        // RoundISODateTime: rounding operates on the receiver's LOCAL wall-clock time (re-resolved to
        // an instant via "compatible" disambiguation afterward), not on the raw epoch nanoseconds -
        // rounding to, say, the nearest 4-hour mark means the nearest local 4-hour mark (00/04/08/12/
        // 16/20 local), not the nearest UTC-epoch-anchored one, which would land on the wrong hour for
        // any zone whose offset isn't itself a multiple of the increment - see
        // round/rounding-increments.js. This also sidesteps entirely the sign complications a raw
        // (possibly negative, pre-1970) epoch-nanosecond rounding would otherwise need (a local
        // nanosecond-of-day is always non-negative) - see round/negative-time.js and
        // round/rounding-direction.js, both of which this same local-time path already gets right.
        return roundToLocalUnit(receiver, smallestUnit, increment, mode);
    }

    // Rounding to a whole day is time-zone-aware: a local day is not fixed-length (86,400s) once DST
    // is involved (it can be 23/24/25 hours). This rounds the offset into the *current* local day
    // (measured against that day's own real length) rather than the raw epoch nanoseconds.
    private static JsValue roundToCalendarDay(JsTemporalZonedDateTime receiver, RoundingMode mode) {
        final var zdt = receiver.toJavaZonedDateTime();
        final var startOfDay = zdt.toLocalDate().atStartOfDay(receiver.zone());
        final var startOfNextDay = zdt.toLocalDate().plusDays(1).atStartOfDay(receiver.zone());
        // GetStartOfDay for both today and tomorrow must be representable, even though only their
        // difference/whichever boundary wins is observable - see round/day-rounding-out-of-range.js
        // and round/get-start-of-day-throws.js.
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(startOfDay));
        JsTemporalInstant.fromEpochNanoseconds(epochNanosOf(startOfNextDay));
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

    // The direction argument is either a bare string or an options-bag-like object carrying a
    // `direction` property (GetOptionsObject-then-Get); anything else (including undefined) is a
    // TypeError before any string coercion is attempted.
    private static JsValue getTimeZoneTransition(JsTemporalZonedDateTime receiver, JsValue directionArg,
            InterpreterOps ops) {
        final JsValue directionValue;
        if (directionArg instanceof JsString) {
            directionValue = directionArg;
        } else if (InterpreterUtils.isObjectLike(directionArg)) {
            directionValue = ops.getMember(directionArg, new JsString("direction"));
        } else {
            throw new TypeErrorException(
                    "Temporal.ZonedDateTime.prototype.getTimeZoneTransition argument must be a string or an object");
        }
        final var direction = JsCoercion.toStr(directionValue, ops);
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

    // Every option is read and cast in alphabetical order (calendarName, fractionalSecondDigits,
    // offset, roundingMode, smallestUnit, timeZoneName) - via optionOrUndefined, which itself throws
    // TypeError for a defined non-object optionsArg the first time any option is touched - before any
    // algorithmic validation (smallestUnit must be minute-or-finer, fractionalSecondDigits range)
    // runs; toString() takes no roundingIncrement (display rounding is always by 1 unit).
    private static JsValue toStringMethod(JsTemporalZonedDateTime receiver, JsValue optionsArg, InterpreterOps ops) {
        final var calendarName = readCalendarNameOption(optionsArg, ops);
        final var fsdValue = optionOrUndefined(optionsArg, "fractionalSecondDigits", ops);
        Integer fsdDigits = null;
        if (!(fsdValue instanceof JsUndefined)) {
            if (fsdValue instanceof JsNumber) {
                final var numeric = JsCoercion.toNumber(fsdValue, ops);
                if (Double.isNaN(numeric)) {
                    throw new RangeErrorException("fractionalSecondDigits must not be NaN");
                }
                final var floored = (int) Math.floor(numeric);
                if (floored < 0 || floored > 9) {
                    throw new RangeErrorException("fractionalSecondDigits must be 0..9 or \"auto\", got " + floored);
                }
                fsdDigits = floored;
            } else if (!"auto".equals(JsCoercion.toStr(fsdValue, ops))) {
                throw new RangeErrorException("fractionalSecondDigits must be 0..9 or \"auto\"");
            }
        }
        final var offsetOption = readOffsetDisplayOption(optionsArg, ops);
        final var mode = readRoundingModeOption(optionsArg, ops, RoundingMode.TRUNC);
        final var smallestUnitValue = optionOrUndefined(optionsArg, "smallestUnit", ops);
        Unit smallestUnit = null;
        if (!(smallestUnitValue instanceof JsUndefined)) {
            smallestUnit = Unit.parseTemporalUnit(JsCoercion.toStr(smallestUnitValue, ops));
        }
        final var timeZoneOption = readTimeZoneNameOption(optionsArg, ops);

        if (smallestUnit == Unit.MINUTE) {
            final var zoned = roundToLocalUnit(receiver, Unit.MINUTE, 1, mode);
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
        JsTemporalZonedDateTime zoned = receiver;
        Integer fractionDigits = null;
        if (smallestUnit != null) {
            requireSecondOrSmallerUnit(smallestUnit);
            zoned = roundToLocalUnit(receiver, smallestUnit, 1, mode);
            fractionDigits = digitsForUnit(smallestUnit);
        } else if (fsdDigits != null) {
            zoned = roundToLocalFractionalDigits(receiver, fsdDigits, mode);
            fractionDigits = fsdDigits;
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

package org.techhouse.simplejs.values;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;

/**
 * A JavaScript {@code Temporal.ZonedDateTime} value: an exact instant (the same
 * {@code (long epochSeconds, int nanoAdjustment)} pair {@link JsTemporalInstant} uses) composed with
 * a {@code java.time.ZoneId} and the fixed {@code "iso8601"} calendar. The original time zone
 * identifier string is kept alongside the resolved {@link ZoneId}: {@code ZoneId} normalizes an
 * offset identifier's textual form (e.g. {@code ZoneOffset.of("+01").getId()} answers
 * {@code "+01:00"}, not the original text), so a round trip through {@link #toString()} needs the
 * source text, not a re-derived one.
 *
 * <p>All arithmetic/option handling (with/add/subtract/round/until/since/toString formatting,
 * disambiguation) lives in {@code builtins/TemporalZonedDateTimeBuiltins}, mirroring the
 * {@link JsDate}/{@code DateBuiltins} split every other Temporal type already follows; this class
 * only ever holds and derives simple views of its own state via {@code java.time.ZonedDateTime}
 * composed on demand.
 */
public final class JsTemporalZonedDateTime extends JsValue {
    public record IsoFieldsAt(Iso8601Fields date, IsoTimeFields time) {
    }

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger MILLIS_DIVISOR = BigInteger.valueOf(1_000_000L);

    private PropertyTable table;
    private final long epochSeconds;
    private final int nanoAdjustment;
    private final ZoneId zone;
    private final String timeZoneId;

    public JsTemporalZonedDateTime(long epochSeconds, int nanoAdjustment, ZoneId zone, String timeZoneId) {
        if (nanoAdjustment < 0 || nanoAdjustment > 999_999_999) {
            throw new IllegalArgumentException("nanoAdjustment must be in 0..999999999, got " + nanoAdjustment);
        }
        this.epochSeconds = epochSeconds;
        this.nanoAdjustment = nanoAdjustment;
        this.zone = zone;
        this.timeZoneId = timeZoneId;
    }

    public static JsTemporalZonedDateTime fromEpochNanoseconds(BigInteger nanoseconds, ZoneId zone, String timeZoneId) {
        final var instant = JsTemporalInstant.fromEpochNanoseconds(nanoseconds);
        return new JsTemporalZonedDateTime(instant.epochSecondsPart(), instant.nanoAdjustment(), zone, timeZoneId);
    }

    public static JsTemporalZonedDateTime fromJavaZonedDateTime(ZonedDateTime zdt, String timeZoneId) {
        return new JsTemporalZonedDateTime(zdt.toEpochSecond(), zdt.getNano(), zdt.getZone(), timeZoneId);
    }

    public long epochSecondsPart() {
        return epochSeconds;
    }

    public int nanoAdjustment() {
        return nanoAdjustment;
    }

    public ZoneId zone() {
        return zone;
    }

    public String timeZoneId() {
        return timeZoneId;
    }

    public BigInteger epochNanoseconds() {
        return BigInteger.valueOf(epochSeconds).multiply(NANOS_PER_SECOND).add(BigInteger.valueOf(nanoAdjustment));
    }

    // Rounds toward negative infinity, per spec: mirrors JsTemporalInstant.epochMillisecondsLong.
    public long epochMillisecondsLong() {
        final var nanos = epochNanoseconds();
        return nanos.subtract(nanos.mod(MILLIS_DIVISOR)).divide(MILLIS_DIVISOR).longValueExact();
    }

    public JsTemporalInstant toInstant() {
        return new JsTemporalInstant(epochSeconds, nanoAdjustment);
    }

    public Instant toJavaInstant() {
        return Instant.ofEpochSecond(epochSeconds, nanoAdjustment);
    }

    public ZonedDateTime toJavaZonedDateTime() {
        return toJavaInstant().atZone(zone);
    }

    public ZoneOffset offset() {
        return zone.getRules().getOffset(toJavaInstant());
    }

    public IsoFieldsAt isoFieldsAtLocal() {
        final var zdt = toJavaZonedDateTime();
        final var nanoOfSecond = zdt.getNano();
        final var date = new Iso8601Fields(zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        final var time = new IsoTimeFields(zdt.getHour(), zdt.getMinute(), zdt.getSecond(), nanoOfSecond / 1_000_000,
                (nanoOfSecond / 1_000) % 1_000, nanoOfSecond % 1_000);
        return new IsoFieldsAt(date, time);
    }

    // Deliberately not compareTo/Comparable, matching JsTemporalInstant's own equivalent method: this
    // type does not implement java.lang.Comparable (no natural total order beyond exact-time
    // comparison is exposed to script code).
    public int compareEpoch(JsTemporalZonedDateTime other) {
        final var cmp = Long.compare(epochSeconds, other.epochSeconds);
        return cmp != 0 ? cmp : Integer.compare(nanoAdjustment, other.nanoAdjustment);
    }

    // Temporal.ZonedDateTime.prototype.equals compares exact time AND time zone identifier (the
    // calendar is always "iso8601" in this engine, so it never differs).
    public boolean isEqualTo(JsTemporalZonedDateTime other) {
        return epochSeconds == other.epochSeconds && nanoAdjustment == other.nanoAdjustment
                && timeZoneId.equals(other.timeZoneId);
    }

    @Override
    public String toString() {
        final var fields = isoFieldsAtLocal();
        final var offsetText = TemporalFormatter.formatOffset(offset());
        return TemporalFormatter.formatZonedDateTime(fields.date(), fields.time(), null, offsetText, timeZoneId,
                TemporalFormatter.TimeZoneNameOption.AUTO, TemporalFormatter.OffsetOption.AUTO,
                TemporalFormatter.CalendarName.AUTO);
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

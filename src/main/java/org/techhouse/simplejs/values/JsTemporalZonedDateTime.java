package org.techhouse.simplejs.values;

import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;

public final class JsTemporalZonedDateTime extends JsValue {
    public record IsoFieldsAt(Iso8601Fields date, IsoTimeFields time) {
    }

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

    public int compareEpoch(JsTemporalZonedDateTime other) {
        final var cmp = Long.compare(epochSeconds, other.epochSeconds);
        return cmp != 0 ? cmp : Integer.compare(nanoAdjustment, other.nanoAdjustment);
    }

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

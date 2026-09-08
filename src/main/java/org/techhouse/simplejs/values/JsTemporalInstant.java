package org.techhouse.simplejs.values;

import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MAX_EPOCH_NANOS;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MIN_EPOCH_NANOS;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;

public final class JsTemporalInstant extends JsValue {
    public record IsoFieldsAt(Iso8601Fields date, IsoTimeFields time) {
    }

    private static final BigInteger MILLIS_DIVISOR = BigInteger.valueOf(1_000_000L);

    private PropertyTable table;
    private final long epochSeconds;
    private final int nanoAdjustment;

    public JsTemporalInstant(long epochSeconds, int nanoAdjustment) {
        if (nanoAdjustment < 0 || nanoAdjustment > 999_999_999) {
            throw new IllegalArgumentException("nanoAdjustment must be in 0..999999999, got " + nanoAdjustment);
        }
        this.epochSeconds = epochSeconds;
        this.nanoAdjustment = nanoAdjustment;
    }

    public static JsTemporalInstant fromEpochNanoseconds(BigInteger nanoseconds) {
        if (nanoseconds.compareTo(MIN_EPOCH_NANOS) < 0 || nanoseconds.compareTo(MAX_EPOCH_NANOS) > 0) {
            throw new RangeErrorException("Instant epoch nanoseconds out of range: " + nanoseconds);
        }
        final var parts = nanoseconds.divideAndRemainder(NANOS_PER_SECOND);
        var seconds = parts[0];
        var nanos = parts[1];
        if (nanos.signum() < 0) {
            nanos = nanos.add(NANOS_PER_SECOND);
            seconds = seconds.subtract(BigInteger.ONE);
        }
        return new JsTemporalInstant(seconds.longValueExact(), nanos.intValueExact());
    }

    public static JsTemporalInstant fromEpochMilliseconds(double epochMilliseconds) {
        if (!Double.isFinite(epochMilliseconds) || epochMilliseconds != Math.floor(epochMilliseconds)) {
            throw new RangeErrorException("Instant epoch milliseconds must be a finite integer");
        }
        final var millis = BigInteger.valueOf((long) epochMilliseconds);
        return fromEpochNanoseconds(millis.multiply(MILLIS_DIVISOR));
    }

    public long epochSecondsPart() {
        return epochSeconds;
    }

    public int nanoAdjustment() {
        return nanoAdjustment;
    }

    public BigInteger epochNanoseconds() {
        return BigInteger.valueOf(epochSeconds).multiply(NANOS_PER_SECOND).add(BigInteger.valueOf(nanoAdjustment));
    }

    public long epochMillisecondsLong() {
        final var nanos = epochNanoseconds();
        return nanos.subtract(nanos.mod(MILLIS_DIVISOR)).divide(MILLIS_DIVISOR).longValueExact();
    }

    public int compareEpoch(JsTemporalInstant other) {
        final var cmp = Long.compare(epochSeconds, other.epochSeconds);
        return cmp != 0 ? cmp : Integer.compare(nanoAdjustment, other.nanoAdjustment);
    }

    public boolean isEqualTo(JsTemporalInstant other) {
        return epochSeconds == other.epochSeconds && nanoAdjustment == other.nanoAdjustment;
    }

    public Instant toJavaInstant() {
        return Instant.ofEpochSecond(epochSeconds, nanoAdjustment);
    }

    public ZonedDateTime atZone(ZoneId zone) {
        return toJavaInstant().atZone(zone);
    }

    public IsoFieldsAt isoFieldsAt(ZoneOffset offset) {
        final var odt = toJavaInstant().atOffset(offset);
        final var nanoOfSecond = odt.getNano();
        final var date = new Iso8601Fields(odt.getYear(), odt.getMonthValue(), odt.getDayOfMonth());
        final var time = new IsoTimeFields(odt.getHour(), odt.getMinute(), odt.getSecond(), nanoOfSecond / 1_000_000,
                (nanoOfSecond / 1_000) % 1_000, nanoOfSecond % 1_000);
        return new IsoFieldsAt(date, time);
    }

    @Override
    public String toString() {
        final var fields = isoFieldsAt(ZoneOffset.UTC);
        return TemporalFormatter.formatDate(fields.date()) + "T" + TemporalFormatter.formatTime(fields.time(), null)
                + "Z";
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

package org.techhouse.simplejs.values;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.TemporalFormatter;

/**
 * A JavaScript {@code Temporal.Instant} value: an exact point on the timeline with nanosecond
 * precision and no calendar or time zone attached. Internally this is a
 * {@code (long epochSeconds, int nanoAdjustment)} pair (nanoAdjustment always {@code 0..999_999_999},
 * Java-style non-negative) rather than a single epoch-millis {@code double} like {@link JsDate}: a
 * double's 53-bit mantissa cannot hold nanosecond precision across the full range Temporal requires,
 * and the spec's own signed-nanoseconds-since-epoch representation is only materialized (as a
 * {@code BigInteger}, and a {@code JsBigInt} at the builtins layer) at the API boundary via
 * {@link #epochNanoseconds()}. {@code java.time.Instant}'s own range (+/-10^9 years) comfortably
 * covers Temporal's +/-10^8 days, so the pair is composed into one on demand ({@link
 * #toJavaInstant()}) for zone/offset math rather than reimplementing it - composition, not
 * inheritance, since {@code java.time.Instant} carries no wider range this type would actually need.
 *
 * <p>All arithmetic/option handling (add/subtract/round/until/since/toString formatting, ISO string
 * parsing) lives in {@code builtins/TemporalInstantBuiltins} instead of here, mirroring the
 * {@link JsDate}/{@code DateBuiltins} split: this class only ever holds and derives simple views of
 * its own state.
 */
public final class JsTemporalInstant extends JsValue {
    public record IsoFieldsAt(Iso8601Fields date, IsoTimeFields time) {
    }

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger NANOS_PER_DAY = BigInteger.valueOf(86_400_000_000_000L);
    private static final BigInteger MAX_EPOCH_NANOSECONDS = BigInteger.valueOf(100_000_000L).multiply(NANOS_PER_DAY);
    private static final BigInteger MIN_EPOCH_NANOSECONDS = MAX_EPOCH_NANOSECONDS.negate();
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
        if (nanoseconds.compareTo(MIN_EPOCH_NANOSECONDS) < 0 || nanoseconds.compareTo(MAX_EPOCH_NANOSECONDS) > 0) {
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
        // NumberToBigInt requires an integral Number - a fractional millisecond count is a RangeError,
        // not silently truncated.
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

    // Rounds toward negative infinity, per spec: BigInteger#mod always answers a non-negative
    // remainder, so subtracting it before dividing yields floor division for a negative dividend too.
    public long epochMillisecondsLong() {
        final var nanos = epochNanoseconds();
        return nanos.subtract(nanos.mod(MILLIS_DIVISOR)).divide(MILLIS_DIVISOR).longValueExact();
    }

    public double epochMilliseconds() {
        return epochMillisecondsLong();
    }

    // Deliberately not named compareTo/Comparable: this type does not implement java.lang.Comparable
    // (no natural total order is exposed to script code beyond this epoch comparison), so a
    // compareTo(JsTemporalInstant)-shaped method without the interface and matching equals() trips
    // SpotBugs' Comparable-pattern detectors.
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

    // The canonical string per Temporal.Instant.prototype.toString with no options: always UTC (the
    // "Z" designator), since an Instant carries no time zone of its own to render an offset from.
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

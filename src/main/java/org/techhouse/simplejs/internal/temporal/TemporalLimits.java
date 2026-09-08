package org.techhouse.simplejs.internal.temporal;

import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_DAY;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND;

import java.math.BigInteger;

public final class TemporalLimits {
    public static final Iso8601Fields MIN_ISO_DATE = new Iso8601Fields(-271_821, 4, 19);
    public static final Iso8601Fields MAX_ISO_DATE = new Iso8601Fields(275_760, 9, 13);

    public static final int MIN_ISO_YEAR = MIN_ISO_DATE.year();
    public static final int MAX_ISO_YEAR = MAX_ISO_DATE.year();
    public static final int MIN_ISO_YEAR_MONTH = MIN_ISO_DATE.month();
    public static final int MAX_ISO_YEAR_MONTH = MAX_ISO_DATE.month();

    public static final long MIN_EPOCH_DAY = -100_000_001L;
    public static final long MAX_EPOCH_DAY = 100_000_000L;

    public static final BigInteger MAX_EPOCH_NANOS = BigInteger.valueOf(MAX_EPOCH_DAY).multiply(NANOS_PER_DAY);
    public static final BigInteger MIN_EPOCH_NANOS = MAX_EPOCH_NANOS.negate();

    public static final double DURATION_DATE_LIMIT = 4_294_967_296.0;
    public static final BigInteger DURATION_TIME_LIMIT = BigInteger.valueOf(9_007_199_254_740_992L)
            .multiply(NANOS_PER_SECOND);

    public static final IsoTimeFields MIDNIGHT = new IsoTimeFields(0, 0, 0, 0, 0, 0);

    private TemporalLimits() {
    }
}

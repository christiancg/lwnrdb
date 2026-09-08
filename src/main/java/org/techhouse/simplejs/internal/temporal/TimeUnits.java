package org.techhouse.simplejs.internal.temporal;

import java.math.BigInteger;

public final class TimeUnits {
    public static final BigInteger NANOS_PER_DAY = BigInteger.valueOf(86_400_000_000_000L);
    public static final BigInteger NANOS_PER_HOUR = BigInteger.valueOf(3_600_000_000_000L);
    public static final BigInteger NANOS_PER_MINUTE = BigInteger.valueOf(60_000_000_000L);
    public static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    public static final BigInteger NANOS_PER_MILLI = BigInteger.valueOf(1_000_000L);
    public static final BigInteger NANOS_PER_MICRO = BigInteger.valueOf(1_000L);

    public static final long NANOS_PER_HOUR_LONG = 3_600_000_000_000L;
    public static final long NANOS_PER_MINUTE_LONG = 60_000_000_000L;
    public static final long NANOS_PER_SECOND_LONG = 1_000_000_000L;
    public static final long NANOS_PER_MILLI_LONG = 1_000_000L;
    public static final long NANOS_PER_MICRO_LONG = 1_000L;

    public static final double MS_PER_SECOND = 1000;
    public static final double MS_PER_MINUTE = 60_000;
    public static final double MS_PER_HOUR = 3_600_000;
    public static final double MS_PER_DAY = 86_400_000;

    public static final BigInteger TWO = BigInteger.valueOf(2);

    private TimeUnits() {
    }
}

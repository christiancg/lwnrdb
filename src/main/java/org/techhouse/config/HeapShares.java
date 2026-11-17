package org.techhouse.config;

public final class HeapShares {
    public static final String AUTO = "auto";

    private HeapShares() {
    }

    public static boolean isAuto(String value) {
        return value != null && AUTO.equalsIgnoreCase(value.trim());
    }

    static long sizeOf(ConfigKey key, String value) {
        final var bytes = isAuto(value) ? shareOfHeap(autoFraction(key)) : SizeParser.parse(value);
        return key == ConfigKey.MAX_MEMORY ? Math.min(bytes, maxMemoryCeiling()) : bytes;
    }

    public static boolean clampsMaxMemory(String value) {
        return !isAuto(value) && SizeParser.parse(value) > maxMemoryCeiling();
    }

    public static long maxMemoryCeiling() {
        return shareOfHeap(Globals.MAX_MEMORY_HEAP_CEILING);
    }

    private static double autoFraction(ConfigKey key) {
        return switch (key) {
            case MAX_MEMORY -> Globals.AUTO_MAX_MEMORY_HEAP_FRACTION;
            case AGGREGATE_MAX_RESULT_BYTES -> Globals.AUTO_AGGREGATE_RESULT_HEAP_FRACTION;
            case AGGREGATE_TOTAL_HELD_BYTES -> Globals.AUTO_AGGREGATE_TOTAL_HEAP_FRACTION;
            case TRANSACTION_TOTAL_MAX_BYTES -> Globals.AUTO_TRANSACTION_TOTAL_HEAP_FRACTION;
            default -> throw new IllegalArgumentException(key.key() + " has no auto value");
        };
    }

    private static long shareOfHeap(double fraction) {
        return (long) (Runtime.getRuntime().maxMemory() * fraction);
    }
}

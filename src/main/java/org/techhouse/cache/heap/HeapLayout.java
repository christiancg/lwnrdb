package org.techhouse.cache.heap;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Modifier;
import java.util.List;

public record HeapLayout(int headerBytes, int arrayHeaderBytes, int referenceBytes) {
    private static final int OBJECT_ALIGNMENT = 8;
    private static final int COMPACT_HEADER_BYTES = 8;
    private static final int LEGACY_HEADER_BYTES = 12;
    private static final int ARRAY_LENGTH_BYTES = 4;
    private static final int WIDE_ELEMENT_BYTES = 8;
    private static final int WIDE_ARRAY_HEADER_BYTES = 16;
    private static final int COMPRESSED_REFERENCE_BYTES = 4;
    private static final int WIDE_REFERENCE_BYTES = 8;
    private static final long COMPRESSED_OOPS_HEAP_LIMIT = 32L << 30;
    private static final int MIN_HASH_TABLE_CAPACITY = 16;
    private static final double HASH_LOAD_FACTOR = 0.75;
    private static final int FIRST_ARRAY_LIST_CAPACITY = 10;
    private static final int LATIN1_MAX_CHAR = 0xFF;
    private static final int UTF16_BYTES_PER_CHAR = 2;
    private static final String NO_COMPACT_HEADERS = "-XX:-UseCompactObjectHeaders";
    private static final String NO_COMPRESSED_OOPS = "-XX:-UseCompressedOops";
    private static final String ZGC_COLLECTOR_PREFIX = "ZGC";
    private static final ClassValue<FieldFootprint> FOOTPRINTS = new ClassValue<>() {
        @Override
        @SuppressWarnings("NullableProblems")
        protected FieldFootprint computeValue(Class<?> type) {
            return FieldFootprint.of(type);
        }
    };
    private static final HeapLayout CURRENT = detect();

    public static HeapLayout current() {
        return CURRENT;
    }

    private static HeapLayout detect() {
        final var collectorNames = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList();
        return from(ManagementFactory.getRuntimeMXBean().getInputArguments(), collectorNames,
                Runtime.getRuntime().maxMemory());
    }

    public static HeapLayout from(List<String> inputArguments, List<String> collectorNames, long maxHeapBytes) {
        final var header = inputArguments.contains(NO_COMPACT_HEADERS) ? LEGACY_HEADER_BYTES : COMPACT_HEADER_BYTES;
        final var wideReferences = inputArguments.contains(NO_COMPRESSED_OOPS)
                || maxHeapBytes >= COMPRESSED_OOPS_HEAP_LIMIT
                || collectorNames.stream().anyMatch(name -> name.startsWith(ZGC_COLLECTOR_PREFIX));
        return new HeapLayout(header, header + ARRAY_LENGTH_BYTES,
                wideReferences ? WIDE_REFERENCE_BYTES : COMPRESSED_REFERENCE_BYTES);
    }

    public static Class<?> jdkClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public long shallowSize(Class<?> type) {
        final var footprint = FOOTPRINTS.get(type);
        return align(headerBytes + footprint.primitiveBytes() + (long) footprint.references() * referenceBytes);
    }

    public long arraySize(int elementBytes, int length) {
        final var header = elementBytes >= WIDE_ELEMENT_BYTES
                ? Math.max(arrayHeaderBytes, WIDE_ARRAY_HEADER_BYTES)
                : arrayHeaderBytes;
        return align(header + (long) elementBytes * length);
    }

    public long referenceArraySize(int length) {
        return arraySize(referenceBytes, length);
    }

    public long stringSize(String value) {
        return shallowSize(String.class) + arraySize(1, backingArrayLength(value));
    }

    private static int backingArrayLength(String value) {
        for (var i = 0; i < value.length(); i++) {
            if (value.charAt(i) > LATIN1_MAX_CHAR) {
                return value.length() * UTF16_BYTES_PER_CHAR;
            }
        }
        return value.length();
    }

    public long hashTableSize(int entries) {
        if (entries == 0) {
            return 0L;
        }
        var capacity = MIN_HASH_TABLE_CAPACITY;
        while (entries > capacity * HASH_LOAD_FACTOR) {
            capacity <<= 1;
        }
        return referenceArraySize(capacity);
    }

    public long presizedHashTableSize(int entries) {
        if (entries == 0) {
            return 0L;
        }
        final var requested = (int) Math.ceil(entries / HASH_LOAD_FACTOR);
        return referenceArraySize(Integer.highestOneBit(Math.max(1, requested - 1)) << 1);
    }

    public long arrayListArraySize(int size) {
        if (size == 0) {
            return 0L;
        }
        var capacity = FIRST_ARRAY_LIST_CAPACITY;
        while (capacity < size) {
            capacity += capacity >> 1;
        }
        return referenceArraySize(capacity);
    }

    private static long align(long bytes) {
        return (bytes + OBJECT_ALIGNMENT - 1) / OBJECT_ALIGNMENT * OBJECT_ALIGNMENT;
    }

    private record FieldFootprint(long primitiveBytes, int references) {
        static FieldFootprint of(Class<?> type) {
            var primitiveBytes = 0L;
            var references = 0;
            for (var current = type; current != null; current = current.getSuperclass()) {
                for (final var field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (field.getType().isPrimitive()) {
                        primitiveBytes += primitiveBytes(field.getType());
                    } else {
                        references++;
                    }
                }
            }
            return new FieldFootprint(primitiveBytes, references);
        }

        private static int primitiveBytes(Class<?> primitive) {
            if (primitive == long.class || primitive == double.class) {
                return Long.BYTES;
            }
            if (primitive == int.class || primitive == float.class) {
                return Integer.BYTES;
            }
            if (primitive == short.class || primitive == char.class) {
                return Short.BYTES;
            }
            return Byte.BYTES;
        }
    }
}

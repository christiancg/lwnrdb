package org.techhouse.ops.admin;

import java.util.ArrayList;
import java.util.List;

public final class RestampedRecords {
    private static final ThreadLocal<List<AdminRecordKey>> COLLECTED = new ThreadLocal<>();

    private RestampedRecords() {
    }

    public static void begin() {
        COLLECTED.set(new ArrayList<>());
    }

    static void add(AdminRecordKey key) {
        final var collected = COLLECTED.get();
        if (collected != null) {
            collected.add(key);
        }
    }

    public static List<AdminRecordKey> drain() {
        final var collected = COLLECTED.get();
        COLLECTED.remove();
        return collected == null ? List.of() : collected;
    }
}

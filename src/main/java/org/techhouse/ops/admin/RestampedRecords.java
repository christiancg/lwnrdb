package org.techhouse.ops.admin;

import java.util.ArrayList;
import java.util.List;

public final class RestampedRecords {
    private final List<AdminRecordKey> keys = new ArrayList<>();

    void add(AdminRecordKey key) {
        keys.add(key);
    }

    public List<AdminRecordKey> keys() {
        return List.copyOf(keys);
    }
}

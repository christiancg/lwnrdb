package org.techhouse.cluster.msg;

import java.util.List;
import org.techhouse.cluster.admin.AdminRecord;

public class AdminSnapshotPayload {
    private List<AdminRecord> records = List.of();

    @SuppressWarnings("unused")
    public AdminSnapshotPayload() {
    }

    public AdminSnapshotPayload(List<AdminRecord> records) {
        this.records = records == null ? List.of() : records;
    }

    public List<AdminRecord> getRecords() {
        return records == null ? List.of() : records;
    }
}

package org.techhouse.cluster.msg;

import java.util.List;
import org.techhouse.cluster.admin.AdminRecord;
import org.techhouse.ejson.NullsOmitted;

@NullsOmitted
public class AdminSnapshotPayload {
    private List<AdminRecord> records = List.of();
    private int tombstoneBucketCount;
    private List<String> tombstoneBuckets;

    @SuppressWarnings("unused")
    public AdminSnapshotPayload() {
    }

    public AdminSnapshotPayload(List<AdminRecord> records) {
        this.records = records == null ? List.of() : records;
    }

    public static AdminSnapshotPayload request(int tombstoneBucketCount, List<String> tombstoneBuckets) {
        final var request = new AdminSnapshotPayload(List.of());
        request.tombstoneBucketCount = tombstoneBucketCount;
        request.tombstoneBuckets = tombstoneBuckets;
        return request;
    }

    public List<AdminRecord> getRecords() {
        return records == null ? List.of() : records;
    }

    public int getTombstoneBucketCount() {
        return tombstoneBucketCount;
    }

    public List<String> getTombstoneBuckets() {
        return tombstoneBuckets;
    }
}

package org.techhouse.cluster.admin;

import java.util.Map;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminTombstone;

final class CaseVariant {
    enum Resolution {
        INSTALL, BURIED, RETRY
    }

    interface Removal {
        boolean remove(AdminRecordKey sibling) throws Exception;
    }

    private CaseVariant() {
    }

    static Resolution resolve(AdminRecord record, AdminRecordKey sibling, Map<String, Long> tombstones, Removal removal)
            throws Exception {
        if (sibling == null) {
            return Resolution.INSTALL;
        }
        final var siblingRecord = AdminRecords.live(sibling);
        if (siblingRecord != null && !record.outranks(siblingRecord)) {
            bury(record.key(), siblingRecord.version(), tombstones);
            return Resolution.BURIED;
        }
        if (!removal.remove(sibling)) {
            return Resolution.RETRY;
        }
        bury(sibling, record.version(), tombstones);
        return Resolution.INSTALL;
    }

    private static void bury(AdminRecordKey key, long version, Map<String, Long> tombstones) throws Exception {
        AdminTombstone.recordAt(key, version);
        tombstones.put(key.id(), version);
    }
}

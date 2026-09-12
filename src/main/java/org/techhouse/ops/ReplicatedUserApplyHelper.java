package org.techhouse.ops;

import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.log.Logger;

// The record carries the coordinator's already-computed password hash, and a replica never re-broadcasts.
public final class ReplicatedUserApplyHelper {
    private static final Logger logger = Logger.logFor(ReplicatedUserApplyHelper.class);

    private ReplicatedUserApplyHelper() {
    }

    public static boolean apply(ReplicationPayload payload) {
        if (payload == null || payload.getOp() == null) {
            return false;
        }
        try {
            switch (payload.getOp()) {
                case UPSERT -> {
                    for (final var document : payload.getDocuments()) {
                        AdminOperationHelper.saveUserEntry(AdminUserEntry.fromJsonObject(document));
                    }
                }
                case DELETE -> {
                    for (final var username : payload.getIds()) {
                        AdminOperationHelper.deleteUserEntry(username);
                    }
                }
                default -> {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to apply replicated user mutation", e);
            return false;
        }
    }
}

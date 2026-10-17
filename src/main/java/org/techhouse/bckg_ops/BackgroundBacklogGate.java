package org.techhouse.bckg_ops;

import java.util.EnumSet;
import java.util.Set;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;

public class BackgroundBacklogGate {
    private static final Set<OperationType> DIRECT_WRITES = EnumSet.of(OperationType.SAVE, OperationType.BULK_SAVE,
            OperationType.DELETE);

    private final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);
    private final Configuration configuration = Configuration.getInstance();

    public ErrorCode admitClientRequest(OperationType type, boolean inTransaction) {
        final var enqueuesBackgroundWork = inTransaction
                ? type == OperationType.COMMIT_TRANSACTION
                : DIRECT_WRITES.contains(type);
        if (!enqueuesBackgroundWork) {
            return null;
        }
        return admitWithin(configuration.longValue(ConfigKey.BACKGROUND_BACKLOG_WAIT_MS));
    }

    public ErrorCode admitForwardedRequest(OperationType type) {
        return DIRECT_WRITES.contains(type) ? admitWithin(0L) : null;
    }

    private ErrorCode admitWithin(long waitMs) {
        try {
            return backgroundTaskManager.awaitCapacity(waitMs) ? null : ErrorCode.BACKGROUND_BACKLOG;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ErrorCode.BACKGROUND_BACKLOG;
        }
    }
}

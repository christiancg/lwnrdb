package org.techhouse.bckg_ops;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.techhouse.log.Logger;

public final class RestartablePool {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 3L;

    private RestartablePool() {
    }

    public static ExecutorService shutdownAndReplace(ExecutorService pool, Logger logger, String workerLabel) {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning(workerLabel + " workers did not terminate within the timeout; abandoning them");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

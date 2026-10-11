package org.techhouse.bckg_ops;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.log.Logger;

public class BackgroundProcessorThread implements Runnable {
    private static final int MAX_BATCH = 256;
    private static final ScopedValue<Boolean> WORKER = ScopedValue.newInstance();

    private final Logger logger = Logger.logFor(BackgroundProcessorThread.class);
    private final LinkedBlockingQueue<Event> queue;
    private final AtomicInteger inFlight;
    private final AtomicInteger parked;
    private final AtomicInteger workerCount;
    private final IdleSignal idleSignal;

    public BackgroundProcessorThread(LinkedBlockingQueue<Event> queue, AtomicInteger inFlight, AtomicInteger parked,
            AtomicInteger workerCount, IdleSignal idleSignal) {
        this.queue = queue;
        this.inFlight = inFlight;
        this.parked = parked;
        this.workerCount = workerCount;
        this.idleSignal = idleSignal;
    }

    public static boolean onWorkerThread() {
        return WORKER.isBound();
    }

    @Override
    public void run() {
        try {
            ScopedValue.where(WORKER, Boolean.TRUE).run(this::workLoop);
        } finally {
            workerCount.updateAndGet(live -> Math.max(0, live - 1));
            idleSignal.signal();
        }
    }

    private void workLoop() {
        final var batch = new ArrayList<Event>(MAX_BATCH);
        while (!Thread.currentThread().isInterrupted()) {
            final Event first;
            parked.incrementAndGet();
            try {
                first = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                parked.decrementAndGet();
            }
            batch.clear();
            batch.add(first);
            while (batch.size() < MAX_BATCH) {
                final var next = queue.poll();
                if (next == null) {
                    break;
                }
                batch.add(next);
            }
            var requeued = 0;
            try {
                final var deferred = EventProcessorHelper.processBatch(batch);
                queue.addAll(deferred);
                requeued = deferred.size();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("Error while processing background task: ", e);
            } finally {
                inFlight.addAndGet(requeued - batch.size());
                idleSignal.signal();
            }
        }
    }
}

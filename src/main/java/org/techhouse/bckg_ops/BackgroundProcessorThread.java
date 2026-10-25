package org.techhouse.bckg_ops;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;
import org.techhouse.ops.admin.PageRowWriteBack;

public class BackgroundProcessorThread implements Runnable {
    private static final int MAX_BATCH = 256;

    private final Logger logger = Logger.logFor(BackgroundProcessorThread.class);
    private final BackgroundLanes lanes;
    private final int ownLane;
    private final AtomicInteger inFlight;
    private final AtomicInteger parked;
    private final AtomicInteger workerCount;
    private final IdleSignal idleSignal;
    private long lastIdleFlush;

    public BackgroundProcessorThread(BackgroundLanes lanes, int ownLane, AtomicInteger inFlight, AtomicInteger parked,
            AtomicInteger workerCount, IdleSignal idleSignal) {
        this.lanes = lanes;
        this.ownLane = ownLane % lanes.count();
        this.inFlight = inFlight;
        this.parked = parked;
        this.workerCount = workerCount;
        this.idleSignal = idleSignal;
    }

    @Override
    public void run() {
        try {
            workLoop();
        } finally {
            workerCount.updateAndGet(live -> Math.max(0, live - 1));
            idleSignal.signal();
        }
    }

    private void workLoop() {
        final var batch = new ArrayList<Event>(MAX_BATCH);
        while (!Thread.currentThread().isInterrupted()) {
            batch.clear();
            if (lanes.stealFor(ownLane, batch, MAX_BATCH) == null) {
                final Event first;
                parked.incrementAndGet();
                try {
                    first = lanes.poll(ownLane);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    parked.decrementAndGet();
                }
                if (first == null) {
                    flushPageRowsWhenIdle();
                    continue;
                }
                batch.add(first);
                lanes.fill(ownLane, batch, MAX_BATCH);
            }
            var requeued = 0;
            try {
                final var deferred = EventProcessorHelper.processBatch(batch);
                lanes.addAll(deferred);
                requeued = deferred.size();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception | Error e) {
                logger.error("Error while processing background task: ", e);
            } finally {
                inFlight.addAndGet(requeued - batch.size());
                idleSignal.signal();
            }
            flushPageRows();
        }
    }

    private void flushPageRowsWhenIdle() {
        final var now = System.currentTimeMillis();
        if (now - lastIdleFlush >= Globals.PAGE_ROWS_WRITE_BACK_MS) {
            lastIdleFlush = now;
            flushPageRows();
        }
    }

    private void flushPageRows() {
        try {
            PageRowWriteBack.flushDue(System.currentTimeMillis());
        } catch (Exception | Error e) {
            logger.error("Error while writing back page-occupancy rows: ", e);
        }
    }
}

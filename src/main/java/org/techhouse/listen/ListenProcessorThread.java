package org.techhouse.listen;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.resp.ListenResponse;

public class ListenProcessorThread implements Runnable {
    private final Logger logger = Logger.logFor(ListenProcessorThread.class);
    private final LinkedBlockingQueue<UUID> dirtyQueue;
    private final ListenManager manager;
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    public ListenProcessorThread(LinkedBlockingQueue<UUID> dirtyQueue, ListenManager manager) {
        this.dirtyQueue = dirtyQueue;
        this.manager = manager;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                final var listenId = dirtyQueue.take();
                processListen(listenId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("Error in listen processor thread: ", e);
            }
        }
    }

    private void processListen(UUID listenId) {
        final var registration = manager.getRegistration(listenId);
        if (registration == null) {
            return;
        }
        final List<JsonObject> results;
        try {
            results = AggregationOperationHelper.processAggregation(registration.request());
        } catch (Exception e) {
            logger.error("Error re-running listen query for " + listenId, e);
            return;
        }
        final var newHash = ResultHasher.hash(results);
        final var oldHash = registration.lastHash().get();
        if (newHash.equals(oldHash)) {
            return;
        }
        if (!registration.lastHash().compareAndSet(oldHash, newHash)) {
            return;
        }
        final var writer = clientTracker.getWriter(registration.clientId());
        if (writer == null) {
            manager.unregister(listenId);
            return;
        }
        final var writerLock = clientTracker.getWriterLock(registration.clientId());
        if (writerLock == null) {
            manager.unregister(listenId);
            return;
        }
        pushUpdate(listenId, results, newHash, writer, writerLock);
    }

    private void pushUpdate(UUID listenId, List<JsonObject> results, String newHash, java.io.BufferedWriter writer,
            ReentrantLock writerLock) {
        final var updateResponse = new ListenResponse(listenId.toString(), results, newHash, true);
        writerLock.lock();
        try {
            writer.write(eJson.toJson(updateResponse));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            manager.unregister(listenId);
        } finally {
            writerLock.unlock();
        }
    }
}

package org.techhouse.listen;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.techhouse.analyze.AggregateDeadline;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AggregateLimitException;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.resp.ListenEndedResponse;
import org.techhouse.ops.resp.ListenResponse;
import org.techhouse.ops.resp.OperationResponse;

public class ListenProcessorThread implements Runnable {
    private final Logger logger = Logger.logFor(ListenProcessorThread.class);
    private final LinkedBlockingQueue<UUID> dirtyQueue;
    private final ListenManager manager;
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration configuration = Configuration.getInstance();

    public ListenProcessorThread(LinkedBlockingQueue<UUID> dirtyQueue, ListenManager manager) {
        this.dirtyQueue = dirtyQueue;
        this.manager = manager;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                final var listenId = dirtyQueue.take();
                manager.dequeued(listenId);
                processListen(listenId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception | Error e) {
                logger.error("Error in listen processor thread: ", e);
            }
        }
    }

    private void processListen(UUID listenId) {
        final var endedListen = manager.endedListen(listenId);
        if (endedListen != null) {
            pushEndedOnceDelivered(listenId, endedListen);
            return;
        }
        final var registration = authorizedRegistration(listenId);
        if (registration == null || !registration.delivered().get()) {
            return;
        }
        final var results = rerunOutsideAnyApply(listenId, registration);
        if (results == null) {
            return;
        }
        final var newHash = ResultHasher.hash(results,
                ResultHasher.ordersResults(registration.request().getAggregationSteps()));
        final var oldHash = registration.lastHash().get();
        if (newHash.equals(oldHash)) {
            return;
        }
        if (!registration.lastHash().compareAndSet(oldHash, newHash)) {
            return;
        }
        push(listenId, registration.clientId(), new ListenResponse(listenId.toString(), results, newHash, true),
                registration);
    }

    private List<JsonObject> rerunOutsideAnyApply(UUID listenId, ListenRegistration registration) {
        final var before = manager.applySnapshot(registration.collectionKeys());
        if (before == null) {
            manager.holdBack(listenId, registration.collectionKeys());
            return null;
        }
        final List<JsonObject> results;
        try {
            results = AggregationOperationHelper.processAggregation(registration.request(),
                    configuration.longValue(ConfigKey.LISTEN_RERUN_TIMEOUT_MS));
        } catch (AggregateDeadline.Expired | AggregateLimitException refused) {
            logger.warning("Listen " + listenId + " was not re-run: " + refused.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Error re-running listen query for " + listenId, e);
            return null;
        }
        if (!manager.unchangedSince(before)) {
            manager.holdBack(listenId, registration.collectionKeys());
            return null;
        }
        return results;
    }

    private void pushEndedOnceDelivered(UUID listenId, ListenManager.EndedListen endedListen) {
        final var registration = endedListen.registration();
        if (registration.delivered().get() && manager.consumeEnded(listenId, endedListen)) {
            push(listenId, registration.clientId(), new ListenEndedResponse(listenId.toString(), endedListen.reason()),
                    null);
        }
    }

    private ListenRegistration authorizedRegistration(UUID listenId) {
        final var registration = manager.getRegistration(listenId);
        if (registration == null || stillAuthorized(registration)) {
            return registration;
        }
        manager.end(listenId, ListenManager.ACCESS_REVOKED);
        return null;
    }

    private boolean stillAuthorized(ListenRegistration registration) {
        final var username = clientTracker.getAuthenticatedUsername(registration.clientId());
        if (username == null) {
            return false;
        }
        final var user = cache.getAdminUserEntry(username);
        return user != null && AuthorizationChecker.check(registration.request(), user).isAllowed();
    }

    private void push(UUID listenId, UUID clientId, OperationResponse frame, ListenRegistration expected) {
        final var outbound = clientTracker.getOutbound(clientId);
        final var writerLock = clientTracker.getWriterLock(clientId);
        if (outbound == null || writerLock == null) {
            manager.unregister(listenId);
            return;
        }
        writerLock.lock();
        try {
            if (expected != null && manager.getRegistration(listenId) != expected) {
                return;
            }
            if (!outbound.offer(OutboundQueue.frameOf(frame))) {
                manager.unregister(listenId);
            }
        } finally {
            writerLock.unlock();
        }
    }
}

package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.DocumentCodec;
import io.github.christiancg.lwnrdb.client.ListenHandler;
import io.github.christiancg.lwnrdb.client.ListenUpdate;
import io.github.christiancg.lwnrdb.client.error.ListenEndedException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import tools.jackson.databind.node.ObjectNode;

final class ListenChannel {
    private static final AtomicInteger DISPATCHER_IDS = new AtomicInteger();
    private static final String LISTEN_ID = "listenId";

    private final WireConnection connection;
    private final RequestEncoder encoder;
    private final Duration requestTimeout;
    private final ExecutorService dispatcher;
    private final Map<String, Registration<?>> registrations = new HashMap<>();
    private final Map<String, List<ObjectNode>> early = new HashMap<>();
    private final ReentrantLock lifecycle = new ReentrantLock();
    private int active;
    private volatile boolean closing;

    ListenChannel(WireConnection connection, RequestEncoder encoder, Duration requestTimeout,
            Consumer<ListenChannel> onLost) {
        this.connection = connection;
        this.encoder = encoder;
        this.requestTimeout = requestTimeout;
        this.dispatcher = Executors.newSingleThreadExecutor(runnable -> {
            final var thread = new Thread(runnable, "lwnrdb-listen-" + DISPATCHER_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        connection.onPush(frame -> dispatch(() -> deliver(frame)));
        connection.onClose(cause -> {
            dispatch(() -> lost(cause));
            onLost.accept(this);
        });
    }

    private void dispatch(Runnable task) {
        try {
            dispatcher.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    boolean isOpen() {
        return !closing && connection.isOpen();
    }

    <T> ListenSubscription register(ObjectNode request, DocumentCodec<T> codec, ListenHandler<T> handler) {
        if (!reserve()) {
            return null;
        }
        final ObjectNode reply;
        try {
            reply = ResponseDecoder.check(connection.request(request, requestTimeout));
        } catch (LwnrdbException e) {
            release();
            throw e;
        }
        final var listenId = reply.path(LISTEN_ID).asString();
        final var subscription = new ListenSubscription(listenId, this);
        final var registration = new Registration<>(listenId, codec, handler, subscription);
        dispatch(() -> activate(registration, reply));
        return subscription;
    }

    void stop(String listenId) {
        try {
            ResponseDecoder.check(connection.request(encoder.stopListen(listenId), requestTimeout));
        } finally {
            dispatch(() -> registrations.remove(listenId));
            release();
        }
    }

    void close() {
        closing = true;
        connection.closeGracefully(requestTimeout);
        dispatcher.shutdown();
    }

    private boolean reserve() {
        lifecycle.lock();
        try {
            if (closing || !connection.isOpen()) {
                return false;
            }
            active++;
            return true;
        } finally {
            lifecycle.unlock();
        }
    }

    private void release() {
        var lastOne = false;
        lifecycle.lock();
        try {
            active--;
            if (active == 0 && !closing) {
                closing = true;
                lastOne = true;
            }
        } finally {
            lifecycle.unlock();
        }
        if (lastOne) {
            close();
        }
    }

    private void activate(Registration<?> registration, ObjectNode initial) {
        registrations.put(registration.listenId(), registration);
        registration.update(initial, true);
        final var buffered = early.remove(registration.listenId());
        if (buffered != null) {
            buffered.forEach(this::deliver);
        }
    }

    private void deliver(ObjectNode frame) {
        final var listenId = frame.path(LISTEN_ID).asString("");
        final var registration = registrations.get(listenId);
        if (registration == null) {
            early.computeIfAbsent(listenId, ignored -> new ArrayList<>()).add(frame);
        } else if (FrameRouter.isListenEnd(frame)) {
            registrations.remove(listenId);
            registration.subscription().markEnded();
            registration.ended((ListenEndedException) ResponseDecoder.raise(frame));
            release();
        } else {
            registration.update(frame, false);
        }
    }

    private void lost(LwnrdbException cause) {
        closing = true;
        registrations.values().forEach(registration -> {
            registration.subscription().markEnded();
            registration.failed(cause);
        });
        registrations.clear();
        early.clear();
        dispatcher.shutdown();
    }

    private record Registration<T>(String listenId, DocumentCodec<T> codec, ListenHandler<T> handler,
            ListenSubscription subscription) {
        void update(ObjectNode frame, boolean initial) {
            try {
                final var results = ResponseDecoder.documents(frame, "results").stream().map(codec::decode).toList();
                handler.onUpdate(new ListenUpdate<>(listenId, results, frame.path("resultHash").asString(""), initial));
            } catch (RuntimeException e) {
                failed(e);
            }
        }

        void ended(ListenEndedException reason) {
            try {
                handler.onEnded(reason);
            } catch (RuntimeException e) {
                failed(e);
            }
        }

        void failed(Throwable error) {
            try {
                handler.onError(error);
            } catch (RuntimeException ignored) {
            }
        }
    }
}

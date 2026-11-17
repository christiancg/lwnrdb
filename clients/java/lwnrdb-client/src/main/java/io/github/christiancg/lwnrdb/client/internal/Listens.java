package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.DocumentCodec;
import io.github.christiancg.lwnrdb.client.ListenHandler;
import io.github.christiancg.lwnrdb.client.Subscription;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.databind.node.ObjectNode;

public final class Listens implements AutoCloseable {
    private final ConnectionFactory factory;
    private final RequestEncoder encoder;
    private final Duration requestTimeout;
    private final List<ListenChannel> channels = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    public Listens(ConnectionFactory factory, RequestEncoder encoder, Duration requestTimeout) {
        this.factory = factory;
        this.encoder = encoder;
        this.requestTimeout = requestTimeout;
    }

    public <T> Subscription listen(String database, String collection, List<PipelineStep> steps, DocumentCodec<T> codec,
            ListenHandler<T> handler) {
        final ObjectNode request = encoder.listen(database, collection, steps);
        lock.lock();
        try {
            for (final var channel : channels) {
                if (channel.isOpen()) {
                    try {
                        final var subscription = channel.register(request, codec, handler);
                        if (subscription != null) {
                            return subscription;
                        }
                    } catch (LwnrdbException e) {
                        rethrowUnlessFull(e);
                    }
                }
            }
            final var channel = new ListenChannel(factory.open(), encoder, requestTimeout, channels::remove);
            channels.add(channel);
            try {
                return Objects.requireNonNull(channel.register(request, codec, handler), "a new channel is open");
            } catch (LwnrdbException e) {
                channels.remove(channel);
                channel.close();
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    public int openChannels() {
        return (int) channels.stream().filter(ListenChannel::isOpen).count();
    }

    @Override
    public void close() {
        channels.forEach(ListenChannel::close);
        channels.clear();
    }

    private static void rethrowUnlessFull(LwnrdbException e) {
        if (!e.hasCode(ErrorCode.TOO_MANY_LISTENS)) {
            throw e;
        }
    }
}

package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;

final class ListenSubscription implements Subscription {
    private final String listenId;
    private final ListenChannel channel;
    private final AtomicBoolean closed = new AtomicBoolean();

    ListenSubscription(String listenId, ListenChannel channel) {
        this.listenId = listenId;
        this.channel = channel;
    }

    @Override
    public String listenId() {
        return listenId;
    }

    @Override
    public boolean isActive() {
        return !closed.get() && channel.isOpen();
    }

    void markEnded() {
        closed.set(true);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && channel.isOpen()) {
            channel.stop(listenId);
        }
    }
}

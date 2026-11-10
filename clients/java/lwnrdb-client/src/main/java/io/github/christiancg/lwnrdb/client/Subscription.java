package io.github.christiancg.lwnrdb.client;

public interface Subscription extends AutoCloseable {
    String listenId();

    boolean isActive();

    @Override
    void close();
}

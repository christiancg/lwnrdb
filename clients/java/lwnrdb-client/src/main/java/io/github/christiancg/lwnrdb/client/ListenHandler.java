package io.github.christiancg.lwnrdb.client;

import io.github.christiancg.lwnrdb.client.error.ListenEndedException;

public interface ListenHandler<T> {
    void onUpdate(ListenUpdate<T> update);

    default void onEnded(ListenEndedException reason) {
    }

    default void onError(Throwable error) {
    }
}

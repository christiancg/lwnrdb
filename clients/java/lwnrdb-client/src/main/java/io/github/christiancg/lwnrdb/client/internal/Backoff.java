package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.error.ConnectionLostException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public interface Backoff {
    void pause(Duration duration);

    static Backoff sleeping() {
        return duration -> {
            final var jittered = duration.toNanos() / 2
                    + ThreadLocalRandom.current().nextLong(Math.max(1, duration.toNanos() / 2 + 1));
            try {
                Thread.sleep(jittered / 1_000_000, (int) (jittered % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConnectionLostException("interrupted while backing off before a retry", e);
            }
        };
    }
}

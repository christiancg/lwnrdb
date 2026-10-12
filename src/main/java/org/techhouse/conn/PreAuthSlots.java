package org.techhouse.conn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;

public class PreAuthSlots {
    private final Map<String, Integer> heldByAddress = new ConcurrentHashMap<>();
    private final IntSupplier limitPerAddress;

    public PreAuthSlots() {
        this(() -> Configuration.getInstance().intValue(ConfigKey.MAX_UNAUTHENTICATED_CONNECTIONS_PER_ADDRESS));
    }

    public PreAuthSlots(IntSupplier limitPerAddress) {
        this.limitPerAddress = limitPerAddress;
    }

    public boolean acquire(String address) {
        final var limit = limitPerAddress.getAsInt();
        if (limit == 0) {
            heldByAddress.merge(address, 1, Integer::sum);
            return true;
        }
        final var admitted = new boolean[1];
        heldByAddress.compute(address, (_, held) -> {
            final int current = held == null ? 0 : held;
            if (current >= limit) {
                return held;
            }
            admitted[0] = true;
            return current + 1;
        });
        return admitted[0];
    }

    public void release(String address) {
        heldByAddress.computeIfPresent(address, (_, held) -> held > 1 ? held - 1 : null);
    }

    public int heldBy(String address) {
        return heldByAddress.getOrDefault(address, 0);
    }
}

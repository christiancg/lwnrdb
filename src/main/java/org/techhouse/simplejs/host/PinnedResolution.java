package org.techhouse.simplejs.host;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PinnedResolution {
    private static final ConcurrentHashMap<String, Entry> PINS = new ConcurrentHashMap<>();

    private static final class Entry {
        private final List<InetAddress> addresses;
        private int holders;

        private Entry(List<InetAddress> addresses) {
            this.addresses = addresses;
        }
    }

    public static final class Lease implements AutoCloseable {
        private final String key;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(String key) {
            this.key = key;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                PINS.computeIfPresent(key, (_, entry) -> --entry.holders == 0 ? null : entry);
            }
        }
    }

    private PinnedResolution() {
    }

    public static Lease pin(String host, List<InetAddress> addresses) {
        final var key = keyOf(host);
        final var checked = List.copyOf(addresses);
        PINS.compute(key, (_, existing) -> {
            final var entry = existing == null ? new Entry(checked) : existing;
            entry.holders++;
            return entry;
        });
        return new Lease(key);
    }

    public static List<InetAddress> lookup(String host) {
        if (host == null) {
            return null;
        }
        final var entry = PINS.get(keyOf(host));
        return entry == null ? null : entry.addresses;
    }

    private static String keyOf(String host) {
        return host.toLowerCase(Locale.ROOT);
    }
}

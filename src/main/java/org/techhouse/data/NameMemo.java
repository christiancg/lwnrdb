package org.techhouse.data;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class NameMemo<V> {
    private final int capacity;
    private final AtomicReference<Generation<V>> current = new AtomicReference<>(new Generation<>());

    private record Generation<V>(ConcurrentHashMap<String, ConcurrentHashMap<String, V>> byOuter, AtomicInteger size) {
        Generation() {
            this(new ConcurrentHashMap<>(), new AtomicInteger());
        }
    }

    public NameMemo(int capacity) {
        this.capacity = capacity;
    }

    public V get(String outer, String inner) {
        if (outer == null || inner == null) {
            return null;
        }
        final var inOuter = current.get().byOuter().get(outer);
        return inOuter == null ? null : inOuter.get(inner);
    }

    public V remember(String outer, String inner, V value) {
        final var generation = withRoom();
        final var known = generation.byOuter().get(outer);
        final var hit = known == null ? null : known.get(inner);
        if (hit != null) {
            return hit;
        }
        final var winner = new AtomicReference<V>();
        generation.byOuter().compute(outer, (_, inOuter) -> {
            final var names = inOuter == null ? new ConcurrentHashMap<String, V>() : inOuter;
            final var previous = names.putIfAbsent(inner, value);
            if (previous == null) {
                generation.size().incrementAndGet();
            }
            winner.set(previous == null ? value : previous);
            return names;
        });
        return winner.get();
    }

    private Generation<V> withRoom() {
        var generation = current.get();
        while (generation.size().get() >= capacity) {
            current.compareAndSet(generation, new Generation<>());
            generation = current.get();
        }
        return generation;
    }

    public void forget(String outer, String inner) {
        if (outer == null || inner == null) {
            return;
        }
        final var generation = current.get();
        generation.byOuter().computeIfPresent(outer, (_, inOuter) -> {
            if (inOuter.remove(inner) != null) {
                generation.size().decrementAndGet();
            }
            return inOuter;
        });
    }

    public void forgetAll(String outer) {
        if (outer == null) {
            return;
        }
        final var generation = current.get();
        final var inOuter = generation.byOuter().remove(outer);
        if (inOuter != null) {
            generation.size().addAndGet(-inOuter.size());
        }
    }

    public void clear() {
        current.set(new Generation<>());
    }

    public int size() {
        return current.get().size().get();
    }
}

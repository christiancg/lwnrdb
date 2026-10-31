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
        final var previous = generation.byOuter().computeIfAbsent(outer, _ -> new ConcurrentHashMap<>())
                .putIfAbsent(inner, value);
        if (previous != null) {
            return previous;
        }
        generation.size().incrementAndGet();
        return value;
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
        final var inOuter = generation.byOuter().get(outer);
        if (inOuter != null && inOuter.remove(inner) != null) {
            generation.size().decrementAndGet();
        }
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

package org.techhouse.ejson.internal;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.techhouse.config.Globals;

public final class KeyTable {
    private static final KeyTable SHARED = new KeyTable(Globals.KEY_TABLE_SLOTS, Globals.KEY_TABLE_MAX_PINNED,
            Globals.KEY_TABLE_PIN_HITS);
    private static final int MIX = 0x9E3779B9;
    private static final int HALF_WORD = 16;

    private record Pinned(String key) {
    }

    private final AtomicReferenceArray<Object> slots;
    private final AtomicIntegerArray referenced;
    private final AtomicIntegerArray hits;
    private final AtomicInteger pinnedCount = new AtomicInteger();
    private final int maxPinned;
    private final int pinHits;
    private final int mask;
    private final int window;

    public KeyTable(int slots) {
        this(slots, slots / 4, Globals.KEY_TABLE_PIN_HITS);
    }

    public KeyTable(int slots, int maxPinned, int pinHits) {
        if (slots < 2 || Integer.bitCount(slots) != 1) {
            throw new IllegalArgumentException("A key table needs a power-of-two slot count of at least 2");
        }
        this.slots = new AtomicReferenceArray<>(slots);
        this.referenced = new AtomicIntegerArray(slots);
        this.hits = new AtomicIntegerArray(slots);
        this.maxPinned = maxPinned;
        this.pinHits = pinHits;
        this.mask = slots - 1;
        this.window = Math.min(Globals.KEY_TABLE_PROBE_WINDOW, slots);
    }

    public static String canonical(CharSequence source, int start, int end) {
        return SHARED.lookup(source, start, end);
    }

    public static String canonical(String key) {
        return SHARED.lookup(key, 0, key.length());
    }

    public static boolean isCanonical(String key) {
        return SHARED.holds(key);
    }

    public static boolean isPinned(String key) {
        return SHARED.holdsPinned(key);
    }

    public String lookup(CharSequence source, int start, int end) {
        final var length = end - start;
        if (length > Globals.KEY_TABLE_MAX_KEY_LENGTH) {
            return source.subSequence(start, end).toString();
        }
        final var hash = hashOf(source, start, end);
        final var home = homeSlot(hash);
        for (var probe = 0; probe < window; probe++) {
            final var slot = (home + probe) & mask;
            final var stored = slots.get(slot);
            final var candidate = keyOf(stored);
            if (candidate != null && candidate.hashCode() == hash && sameText(candidate, source, start, length)) {
                recordHit(slot, stored);
                return candidate;
            }
        }
        return admit(source.subSequence(start, end).toString(), home);
    }

    public boolean holds(String key) {
        return slotHolding(key) != null;
    }

    public boolean holdsPinned(String key) {
        return slotHolding(key) instanceof Pinned;
    }

    private Object slotHolding(String key) {
        if (key == null || key.length() > Globals.KEY_TABLE_MAX_KEY_LENGTH) {
            return null;
        }
        final var home = homeSlot(key.hashCode());
        for (var probe = 0; probe < window; probe++) {
            final var stored = slots.get((home + probe) & mask);
            if (sameInstance(keyOf(stored), key)) {
                return stored;
            }
        }
        return null;
    }

    private static String keyOf(Object stored) {
        return stored instanceof Pinned(String key) ? key : (String) stored;
    }

    private static boolean sameInstance(Object stored, Object candidate) {
        return stored == candidate;
    }

    public int capacity() {
        return slots.length();
    }

    public int occupiedSlots() {
        var occupied = 0;
        for (var slot = 0; slot < slots.length(); slot++) {
            if (slots.get(slot) != null) {
                occupied++;
            }
        }
        return occupied;
    }

    public int pinnedSlots() {
        return pinnedCount.get();
    }

    private String admit(String key, int home) {
        for (var probe = 0; probe < window; probe++) {
            final var slot = (home + probe) & mask;
            var current = slots.get(slot);
            if (current == null) {
                if (slots.compareAndSet(slot, null, key)) {
                    hits.set(slot, 1);
                    return key;
                }
                current = slots.get(slot);
            }
            final var existing = keyOf(current);
            if (key.equals(existing)) {
                return existing;
            }
        }
        for (var probe = 0; probe < window; probe++) {
            final var slot = (home + probe) & mask;
            if (referenced.get(slot) == 0 && slots.get(slot) instanceof String cold
                    && slots.compareAndSet(slot, cold, key)) {
                hits.set(slot, 1);
                return key;
            }
        }
        for (var probe = 0; probe < window; probe++) {
            referenced.set((home + probe) & mask, 0);
        }
        return key;
    }

    private void recordHit(int slot, Object stored) {
        if (!(stored instanceof String key)) {
            return;
        }
        if (referenced.get(slot) == 0) {
            referenced.set(slot, 1);
        }
        if (hits.getAndUpdate(slot, count -> count < pinHits ? count + 1 : count) + 1 >= pinHits) {
            pin(slot, key);
        }
    }

    private void pin(int slot, String key) {
        if (pinnedCount.getAndUpdate(count -> count < maxPinned ? count + 1 : count) >= maxPinned) {
            return;
        }
        if (!slots.compareAndSet(slot, key, new Pinned(key))) {
            pinnedCount.decrementAndGet();
        }
    }

    private int homeSlot(int hash) {
        final var mixed = hash * MIX;
        return (mixed ^ (mixed >>> HALF_WORD)) & mask;
    }

    private static int hashOf(CharSequence source, int start, int end) {
        var hash = 0;
        for (var index = start; index < end; index++) {
            hash = 31 * hash + source.charAt(index);
        }
        return hash;
    }

    private static boolean sameText(String candidate, CharSequence source, int start, int length) {
        if (candidate.length() != length) {
            return false;
        }
        if (source instanceof String text) {
            return candidate.regionMatches(0, text, start, length);
        }
        for (var offset = 0; offset < length; offset++) {
            if (candidate.charAt(offset) != source.charAt(start + offset)) {
                return false;
            }
        }
        return true;
    }
}

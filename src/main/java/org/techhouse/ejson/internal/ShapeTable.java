package org.techhouse.ejson.internal;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.KeyShape;

public final class ShapeTable {
    private static final ShapeTable SHARED = new ShapeTable(Globals.SHAPE_TABLE_SLOTS, Globals.SHAPE_TABLE_MAX_PINNED,
            Globals.KEY_TABLE_PIN_HITS);
    private static final int MIX = 0x9E3779B9;
    private static final int HALF_WORD = 16;

    private final AtomicReferenceArray<KeyShape> shapes;
    private final AtomicIntegerArray recentlyUsed;
    private final AtomicIntegerArray uses;
    private final AtomicInteger pinned = new AtomicInteger();
    private final int maxPinned;
    private final int pinUses;
    private final int mask;
    private final int window;

    public ShapeTable(int slots, int maxPinned, int pinUses) {
        if (slots < 2 || Integer.bitCount(slots) != 1) {
            throw new IllegalArgumentException("A shape table needs a power-of-two slot count of at least 2");
        }
        this.shapes = new AtomicReferenceArray<>(slots);
        this.recentlyUsed = new AtomicIntegerArray(slots);
        this.uses = new AtomicIntegerArray(slots);
        this.maxPinned = maxPinned;
        this.pinUses = pinUses;
        this.mask = slots - 1;
        this.window = Math.min(Globals.KEY_TABLE_PROBE_WINDOW, slots);
    }

    public static KeyShape shared(String[] keys, int from, int to) {
        return SHARED.shapeOf(keys, from, to);
    }

    public KeyShape shapeOf(String[] keys, int from, int to) {
        if (!isShareable(keys, from, to)) {
            return null;
        }
        final var home = homeOf(KeyShape.hashOf(keys, from, to));
        for (var probe = 0; probe < window; probe++) {
            final var slot = (home + probe) & mask;
            final var shape = shapes.get(slot);
            if (shape != null && shape.hasKeys(keys, from, to)) {
                used(slot, shape);
                return shape;
            }
        }
        final var fresh = JsonObject.shapeOf(keys, from, to);
        return fresh == null ? null : admitted(fresh, home);
    }

    private static boolean isShareable(String[] keys, int from, int to) {
        if (to - from > Globals.SHAPE_TABLE_MAX_KEYS) {
            return false;
        }
        for (var i = from; i < to; i++) {
            if (keys[i].length() > Globals.KEY_TABLE_MAX_KEY_LENGTH) {
                return false;
            }
        }
        return true;
    }

    public int pinnedShapes() {
        return pinned.get();
    }

    private KeyShape admitted(KeyShape fresh, int home) {
        for (var probe = 0; probe < window; probe++) {
            final var slot = (home + probe) & mask;
            if (shapes.get(slot) == null && shapes.compareAndSet(slot, null, fresh)) {
                uses.set(slot, 1);
                return fresh;
            }
        }
        for (var probe = 0; probe < window; probe++) {
            final var slot = (home + probe) & mask;
            final var resident = shapes.get(slot);
            if (resident != null && !resident.isPinned() && recentlyUsed.get(slot) == 0
                    && shapes.compareAndSet(slot, resident, fresh)) {
                uses.set(slot, 1);
                return fresh;
            }
        }
        for (var probe = 0; probe < window; probe++) {
            recentlyUsed.set((home + probe) & mask, 0);
        }
        return fresh;
    }

    private void used(int slot, KeyShape shape) {
        if (shape.isPinned()) {
            return;
        }
        recentlyUsed.set(slot, 1);
        if (uses.incrementAndGet(slot) < pinUses
                || pinned.getAndUpdate(count -> count < maxPinned ? count + 1 : count) >= maxPinned) {
            return;
        }
        if (!shape.pin()) {
            pinned.decrementAndGet();
        }
    }

    private int homeOf(int hash) {
        final var mixed = hash * MIX;
        return (mixed ^ (mixed >>> HALF_WORD)) & mask;
    }
}

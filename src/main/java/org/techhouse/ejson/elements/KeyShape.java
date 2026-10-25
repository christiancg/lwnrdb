package org.techhouse.ejson.elements;

public final class KeyShape {
    private final String[] keys;
    private final int[] slots;
    private final int hash;
    private volatile boolean pinned;

    KeyShape(String[] keys, int[] slots, int hash) {
        this.keys = keys;
        this.slots = slots;
        this.hash = hash;
    }

    String[] keys() {
        return keys;
    }

    int[] slots() {
        return slots;
    }

    public int size() {
        return keys.length;
    }

    public int hash() {
        return hash;
    }

    public boolean isPinned() {
        return pinned;
    }

    public synchronized boolean pin() {
        if (pinned) {
            return false;
        }
        pinned = true;
        return true;
    }

    public boolean hasKeys(String[] source, int from, int to) {
        if (to - from != keys.length) {
            return false;
        }
        for (var i = 0; i < keys.length; i++) {
            if (!keys[i].equals(source[from + i])) {
                return false;
            }
        }
        return true;
    }

    public static int hashOf(String[] source, int from, int to) {
        var hash = to - from;
        for (var i = from; i < to; i++) {
            hash = 31 * hash + source[i].hashCode();
        }
        return hash;
    }
}

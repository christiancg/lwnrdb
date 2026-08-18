package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

// [[MapData]] is a list whose deleted records become EMPTY instead of being spliced out, which is
// what makes a live iterator observe an entry added behind it, skip one deleted ahead of it, and
// revisit a key that was deleted and re-added. The list is modelled as a forward-linked chain of
// nodes: a tombstoned node keeps its `next` pointer, so a cursor parked on it can still advance,
// while `prune` unlinks it from the chain so a set/delete loop does not grow without bound.
public final class JsMap extends JsValue {
    private static final int PRUNE_THRESHOLD = 32;

    private PropertyTable table;

    public record Entry(JsValue key, JsValue value) {
    }

    private static final class Node {
        private JsValue key;
        private JsValue value;
        private boolean dead;
        private Node next;
    }

    private final Map<Object, Node> index = new HashMap<>();
    private final boolean weak;
    private Node head;
    private Node tail;
    private int deadCount;

    public JsMap() {
        this(false);
    }

    public JsMap(boolean weak) {
        this.weak = weak;
    }

    public boolean isWeak() {
        return weak;
    }

    public JsValue get(JsValue key) {
        final var node = index.get(SameValueZero.key(key));
        return node == null ? JsUndefined.getInstance() : node.value;
    }

    public void set(JsValue key, JsValue value) {
        final var canonical = SameValueZero.key(key);
        final var existing = index.get(canonical);
        if (existing != null) {
            existing.value = value;
            return;
        }
        final var node = new Node();
        node.key = key;
        node.value = value;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        index.put(canonical, node);
    }

    public boolean has(JsValue key) {
        return index.containsKey(SameValueZero.key(key));
    }

    public boolean delete(JsValue key) {
        final var node = index.remove(SameValueZero.key(key));
        if (node == null) {
            return false;
        }
        tombstone(node);
        return true;
    }

    public void clear() {
        for (var node = head; node != null; node = node.next) {
            markDead(node);
        }
        index.clear();
        prune();
    }

    public int size() {
        return index.size();
    }

    public Collection<Entry> entries() {
        final var snapshot = new ArrayList<Entry>(index.size());
        for (var node = head; node != null; node = node.next) {
            if (!node.dead) {
                snapshot.add(new Entry(node.key, node.value));
            }
        }
        return snapshot;
    }

    public Cursor cursor() {
        return new Cursor();
    }

    private void tombstone(Node node) {
        markDead(node);
        if (deadCount > PRUNE_THRESHOLD && deadCount > index.size()) {
            prune();
        }
    }

    private void markDead(Node node) {
        if (node.dead) {
            return;
        }
        node.dead = true;
        node.key = null;
        node.value = null;
        deadCount++;
    }

    // Unlinks every tombstone from the chain while leaving each one's own `next` pointing at the
    // surviving successor, so a cursor still holding a pruned node walks forward to a live entry.
    private void prune() {
        Node previous = null;
        var node = head;
        while (node != null) {
            if (node.dead) {
                if (previous == null) {
                    head = node.next;
                } else {
                    previous.next = node.next;
                }
            } else {
                previous = node;
            }
            node = node.next;
        }
        tail = previous;
        deadCount = 0;
    }

    // A %MapIteratorPrototype% position: `null` from `next` means the list is exhausted, and the
    // spec's [[Map]]-set-to-undefined step makes that terminal even if entries are appended later.
    public final class Cursor {
        private Node node;
        private boolean started;
        private boolean exhausted;

        public Entry next() {
            if (exhausted) {
                return null;
            }
            var candidate = started ? nextOf(node) : head;
            while (candidate != null && candidate.dead) {
                candidate = candidate.next;
            }
            started = true;
            node = candidate;
            if (candidate == null) {
                exhausted = true;
                return null;
            }
            return new Entry(candidate.key, candidate.value);
        }

        private Node nextOf(Node current) {
            return current == null ? null : current.next;
        }
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

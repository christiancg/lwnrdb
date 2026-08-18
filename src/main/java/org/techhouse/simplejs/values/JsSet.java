package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

// [[SetData]] follows the same append-with-tombstones model as JsMap's [[MapData]]: see the comment
// there for why a deleted member stays linked into the chain and how `prune` keeps it bounded.
public final class JsSet extends JsValue {
    private static final int PRUNE_THRESHOLD = 32;

    private PropertyTable table;

    private static final class Node {
        private JsValue value;
        private boolean dead;
        private Node next;
    }

    private final Map<Object, Node> index = new HashMap<>();
    private final boolean weak;
    private Node head;
    private Node tail;
    private int deadCount;

    public JsSet() {
        this(false);
    }

    public JsSet(boolean weak) {
        this.weak = weak;
    }

    public boolean isWeak() {
        return weak;
    }

    public void add(JsValue value) {
        final var canonical = SameValueZero.key(value);
        if (index.containsKey(canonical)) {
            return;
        }
        final var node = new Node();
        node.value = value;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        index.put(canonical, node);
    }

    public boolean has(JsValue value) {
        return index.containsKey(SameValueZero.key(value));
    }

    public boolean delete(JsValue value) {
        final var node = index.remove(SameValueZero.key(value));
        if (node == null) {
            return false;
        }
        markDead(node);
        if (deadCount > PRUNE_THRESHOLD && deadCount > index.size()) {
            prune();
        }
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

    public Collection<JsValue> values() {
        final var snapshot = new ArrayList<JsValue>(index.size());
        for (var node = head; node != null; node = node.next) {
            if (!node.dead) {
                snapshot.add(node.value);
            }
        }
        return snapshot;
    }

    public Cursor cursor() {
        return new Cursor();
    }

    private void markDead(Node node) {
        if (node.dead) {
            return;
        }
        node.dead = true;
        node.value = null;
        deadCount++;
    }

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

    public final class Cursor {
        private Node node;
        private boolean started;
        private boolean exhausted;

        // `null` means the list is exhausted, and per spec that is terminal for this cursor.
        public JsValue next() {
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
            return candidate.value;
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

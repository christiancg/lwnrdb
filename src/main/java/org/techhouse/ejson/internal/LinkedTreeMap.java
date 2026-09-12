package org.techhouse.ejson.internal;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

public final class LinkedTreeMap<K, V> extends AbstractMap<K, V> implements Serializable {
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Comparator<Comparable> NATURAL_ORDER = Comparable::compareTo;

    private final Comparator<? super K> comparator;
    private final boolean allowNullValues;
    LinkedTreeMapNode<K, V> root;
    int size = 0;
    int modCount = 0;

    final LinkedTreeMapNode<K, V> header;

    @SuppressWarnings("unchecked") // unsafe! this assumes K is comparable
    public LinkedTreeMap(boolean allowNullValues) {
        this((Comparator<? super K>) NATURAL_ORDER, allowNullValues);
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // unsafe! if comparator is null, this assumes K is comparable
    public LinkedTreeMap(Comparator<? super K> comparator, boolean allowNullValues) {
        this.comparator = comparator != null ? comparator : (Comparator) NATURAL_ORDER;
        this.allowNullValues = allowNullValues;
        this.header = new LinkedTreeMapNode<>(allowNullValues);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public V get(Object key) {
        LinkedTreeMapNode<K, V> node = findByObject(key);
        return node != null ? node.value : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return findByObject(key) != null;
    }

    @Override
    public V put(K key, V value) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }
        if (value == null && !allowNullValues) {
            throw new NullPointerException("value == null");
        }
        LinkedTreeMapNode<K, V> created = find(key, true);
        assert created != null;
        V result = created.value;
        created.value = value;
        return result;
    }

    @Override
    public void clear() {
        root = null;
        size = 0;
        modCount++;

        LinkedTreeMapNode<K, V> header = this.header;
        header.next = header.prev = header;
    }

    @Override
    public V remove(Object key) {
        LinkedTreeMapNode<K, V> node = removeInternalByKey(key);
        return node != null ? node.value : null;
    }

    LinkedTreeMapNode<K, V> find(K key, boolean create) {
        Comparator<? super K> comparator = this.comparator;
        LinkedTreeMapNode<K, V> nearest = root;
        int comparison = 0;

        if (nearest != null) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparableKey = (comparator == NATURAL_ORDER) ? (Comparable<Object>) key : null;

            while (true) {
                if ((comparableKey != null)) {
                    assert nearest.key != null;
                    comparison = comparableKey.compareTo(nearest.key);
                } else {
                    comparison = comparator.compare(key, nearest.key);
                }

                if (comparison == 0) {
                    return nearest;
                }

                LinkedTreeMapNode<K, V> child = (comparison < 0) ? nearest.left : nearest.right;
                if (child == null) {
                    break;
                }

                nearest = child;
            }
        }

        if (!create) {
            return null;
        }

        LinkedTreeMapNode<K, V> header = this.header;
        LinkedTreeMapNode<K, V> created;
        if (nearest == null) {
            if (comparator == NATURAL_ORDER && !(key instanceof Comparable)) {
                throw new ClassCastException(key.getClass().getName() + " is not Comparable");
            }
            created = new LinkedTreeMapNode<>(allowNullValues, nearest, key, header, header.prev);
            root = created;
        } else {
            created = new LinkedTreeMapNode<>(allowNullValues, nearest, key, header, header.prev);
            if (comparison < 0) {
                nearest.left = created;
            } else {
                nearest.right = created;
            }
            rebalance(nearest, true);
        }
        size++;
        modCount++;

        return created;
    }

    @SuppressWarnings("unchecked")
    LinkedTreeMapNode<K, V> findByObject(Object key) {
        try {
            return key != null ? find((K) key, false) : null;
        } catch (ClassCastException e) {
            return null;
        }
    }

    LinkedTreeMapNode<K, V> findByEntry(Entry<?, ?> entry) {
        LinkedTreeMapNode<K, V> mine = findByObject(entry.getKey());
        boolean valuesEqual = mine != null && equal(mine.value, entry.getValue());
        return valuesEqual ? mine : null;
    }

    private boolean equal(Object a, Object b) {
        return Objects.equals(a, b);
    }

    void removeInternal(LinkedTreeMapNode<K, V> node, boolean unlink) {
        if (unlink) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
        }

        LinkedTreeMapNode<K, V> left = node.left;
        LinkedTreeMapNode<K, V> right = node.right;
        LinkedTreeMapNode<K, V> originalParent = node.parent;
        if (left != null && right != null) {
            LinkedTreeMapNode<K, V> adjacent = (left.height > right.height) ? left.last() : right.first();
            removeInternal(adjacent, false);

            int leftHeight = 0;
            left = node.left;
            if (left != null) {
                leftHeight = left.height;
                adjacent.left = left;
                left.parent = adjacent;
                node.left = null;
            }

            int rightHeight = 0;
            right = node.right;
            if (right != null) {
                rightHeight = right.height;
                adjacent.right = right;
                right.parent = adjacent;
                node.right = null;
            }

            adjacent.height = Math.max(leftHeight, rightHeight) + 1;
            replaceInParent(node, adjacent);
            return;
        } else if (left != null) {
            replaceInParent(node, left);
            node.left = null;
        } else if (right != null) {
            replaceInParent(node, right);
            node.right = null;
        } else {
            replaceInParent(node, null);
        }

        rebalance(originalParent, false);
        size--;
        modCount++;
    }

    LinkedTreeMapNode<K, V> removeInternalByKey(Object key) {
        LinkedTreeMapNode<K, V> node = findByObject(key);
        if (node != null) {
            removeInternal(node, true);
        }
        return node;
    }

    @SuppressWarnings("ReferenceEquality")
    private void replaceInParent(LinkedTreeMapNode<K, V> node, LinkedTreeMapNode<K, V> replacement) {
        LinkedTreeMapNode<K, V> parent = node.parent;
        node.parent = null;
        if (replacement != null) {
            replacement.parent = parent;
        }

        if (parent != null) {
            if (parent.left == node) {
                parent.left = replacement;
            } else {
                assert parent.right == node;
                parent.right = replacement;
            }
        } else {
            root = replacement;
        }
    }

    private void rebalance(LinkedTreeMapNode<K, V> unbalanced, boolean insert) {
        for (LinkedTreeMapNode<K, V> node = unbalanced; node != null; node = node.parent) {
            LinkedTreeMapNode<K, V> left = node.left;
            LinkedTreeMapNode<K, V> right = node.right;
            int leftHeight = left != null ? left.height : 0;
            int rightHeight = right != null ? right.height : 0;

            int delta = leftHeight - rightHeight;
            if (delta == -2) {
                assert right != null;
                LinkedTreeMapNode<K, V> rightLeft = right.left;
                LinkedTreeMapNode<K, V> rightRight = right.right;
                int rightRightHeight = rightRight != null ? rightRight.height : 0;
                int rightLeftHeight = rightLeft != null ? rightLeft.height : 0;

                int rightDelta = rightLeftHeight - rightRightHeight;
                if (rightDelta == -1 || (rightDelta == 0 && !insert)) {
                    rotateLeft(node);
                } else {
                    assert (rightDelta == 1);
                    rotateRight(right);
                    rotateLeft(node);
                }
                if (insert) {
                    break;
                }

            } else if (delta == 2) {
                assert left != null;
                LinkedTreeMapNode<K, V> leftLeft = left.left;
                LinkedTreeMapNode<K, V> leftRight = left.right;
                int leftRightHeight = leftRight != null ? leftRight.height : 0;
                int leftLeftHeight = leftLeft != null ? leftLeft.height : 0;

                int leftDelta = leftLeftHeight - leftRightHeight;
                if (leftDelta == 1 || (leftDelta == 0 && !insert)) {
                    rotateRight(node);
                } else {
                    assert (leftDelta == -1);
                    rotateLeft(left);
                    rotateRight(node);
                }
                if (insert) {
                    break;
                }

            } else if (delta == 0) {
                node.height = leftHeight + 1;
                if (insert) {
                    break;
                }

            } else {
                assert (delta == -1 || delta == 1);
                node.height = Math.max(leftHeight, rightHeight) + 1;
                if (!insert) {
                    break;
                }
            }
        }
    }

    private void rotateLeft(LinkedTreeMapNode<K, V> root) {
        LinkedTreeMapNode<K, V> left = root.left;
        LinkedTreeMapNode<K, V> pivot = root.right;
        LinkedTreeMapNode<K, V> pivotLeft = pivot.left;
        LinkedTreeMapNode<K, V> pivotRight = pivot.right;

        root.right = pivotLeft;
        if (pivotLeft != null) {
            pivotLeft.parent = root;
        }

        replaceInParent(root, pivot);

        pivot.left = root;
        root.parent = pivot;

        root.height = Math.max(left != null ? left.height : 0, pivotLeft != null ? pivotLeft.height : 0) + 1;
        pivot.height = Math.max(root.height, pivotRight != null ? pivotRight.height : 0) + 1;
    }

    private void rotateRight(LinkedTreeMapNode<K, V> root) {
        LinkedTreeMapNode<K, V> pivot = root.left;
        LinkedTreeMapNode<K, V> right = root.right;
        LinkedTreeMapNode<K, V> pivotLeft = pivot.left;
        LinkedTreeMapNode<K, V> pivotRight = pivot.right;

        root.left = pivotRight;
        if (pivotRight != null) {
            pivotRight.parent = root;
        }

        replaceInParent(root, pivot);

        pivot.right = root;
        root.parent = pivot;

        root.height = Math.max(right != null ? right.height : 0, pivotRight != null ? pivotRight.height : 0) + 1;
        pivot.height = Math.max(root.height, pivotLeft != null ? pivotLeft.height : 0) + 1;
    }

    private EntrySet entrySet;
    private KeySet keySet;

    @Override
    @SuppressWarnings("NullableProblems")
    public Set<Entry<K, V>> entrySet() {
        EntrySet result = entrySet;
        return result != null ? result : (entrySet = new EntrySet());
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public Set<K> keySet() {
        KeySet result = keySet;
        return result != null ? result : (keySet = new KeySet());
    }

    private abstract class LinkedTreeMapIterator<T> implements Iterator<T> {
        LinkedTreeMapNode<K, V> next = header.next;
        LinkedTreeMapNode<K, V> lastReturned = null;
        int expectedModCount = modCount;

        LinkedTreeMapIterator() {
        }

        @Override
        @SuppressWarnings("ReferenceEquality")
        public final boolean hasNext() {
            return next != header;
        }

        @SuppressWarnings("ReferenceEquality")
        final LinkedTreeMapNode<K, V> nextNode() {
            LinkedTreeMapNode<K, V> e = next;
            if (e == header) {
                throw new NoSuchElementException();
            }
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            next = e.next;
            return lastReturned = e;
        }

        @Override
        public final void remove() {
            if (lastReturned == null) {
                throw new IllegalStateException();
            }
            removeInternal(lastReturned, true);
            lastReturned = null;
            expectedModCount = modCount;
        }
    }

    class EntrySet extends AbstractSet<Entry<K, V>> {
        @Override
        public int size() {
            return size;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Iterator<Entry<K, V>> iterator() {
            return new LinkedTreeMapIterator<Entry<K, V>>() {
                @Override
                public Entry<K, V> next() {
                    return nextNode();
                }
            };
        }

        @Override
        public boolean contains(Object o) {
            return o instanceof Entry<?, ?> e && findByEntry(e) != null;
        }

        @Override
        public boolean remove(Object o) {
            if (!(o instanceof Entry<?, ?> e)) {
                return false;
            }

            LinkedTreeMapNode<K, V> node = findByEntry(e);
            if (node == null) {
                return false;
            }
            removeInternal(node, true);
            return true;
        }

        @Override
        public void clear() {
            LinkedTreeMap.this.clear();
        }
    }

    final class KeySet extends AbstractSet<K> {
        @Override
        public int size() {
            return size;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Iterator<K> iterator() {
            return new LinkedTreeMapIterator<K>() {
                @Override
                public K next() {
                    return nextNode().key;
                }
            };
        }

        @Override
        public boolean contains(Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(Object key) {
            return removeInternalByKey(key) != null;
        }

        @Override
        public void clear() {
            LinkedTreeMap.this.clear();
        }
    }

    @Serial
    private Object writeReplace() throws ObjectStreamException {
        return new LinkedHashMap<>(this);
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException {
        throw new InvalidObjectException("Deserialization is unsupported");
    }
}

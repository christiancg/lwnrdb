package org.techhouse.ejson.internal;

import java.util.Map;

final class LinkedTreeMapNode<K, V> implements Map.Entry<K, V> {
    LinkedTreeMapNode<K, V> parent;
    LinkedTreeMapNode<K, V> left;
    LinkedTreeMapNode<K, V> right;
    LinkedTreeMapNode<K, V> next;
    LinkedTreeMapNode<K, V> prev;
    final K key;
    final boolean allowNullValue;
    V value;
    int height;

    LinkedTreeMapNode(boolean allowNullValue) {
        key = null;
        this.allowNullValue = allowNullValue;
        next = prev = this;
    }

    LinkedTreeMapNode(boolean allowNullValue, LinkedTreeMapNode<K, V> parent, K key, LinkedTreeMapNode<K, V> next,
            LinkedTreeMapNode<K, V> prev) {
        this.parent = parent;
        this.key = key;
        this.allowNullValue = allowNullValue;
        this.height = 1;
        this.next = next;
        this.prev = prev;
        prev.next = this;
        next.prev = this;
    }

    @Override
    public K getKey() {
        return key;
    }

    @Override
    public V getValue() {
        return value;
    }

    @Override
    public V setValue(V value) {
        if (value == null && !allowNullValue) {
            throw new NullPointerException("value == null");
        }
        V oldValue = this.value;
        this.value = value;
        return oldValue;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Map.Entry<?, ?> other) {
            return (key == null ? other.getKey() == null : key.equals(other.getKey()))
                    && (value == null ? other.getValue() == null : value.equals(other.getValue()));
        }
        return false;
    }

    @Override
    public int hashCode() {
        return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
    }

    @Override
    public String toString() {
        return key + "=" + value;
    }

    public LinkedTreeMapNode<K, V> first() {
        LinkedTreeMapNode<K, V> node = this;
        LinkedTreeMapNode<K, V> child = node.left;
        while (child != null) {
            node = child;
            child = node.left;
        }
        return node;
    }

    public LinkedTreeMapNode<K, V> last() {
        LinkedTreeMapNode<K, V> node = this;
        LinkedTreeMapNode<K, V> child = node.right;
        while (child != null) {
            node = child;
            child = node.right;
        }
        return node;
    }
}

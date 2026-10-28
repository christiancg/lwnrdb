package org.techhouse.simplejs.internal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.techhouse.simplejs.values.JsValue;

final class BindingTable {
    static final int SMALL = 8;

    static final class Binding {
        private JsValue value;
        private LazyBuiltin lazy;
        final boolean isVar;
        final boolean isConst;
        boolean initialized;
        final boolean enumerable;
        final boolean writable;
        final boolean configurable;

        Binding(JsValue value, String kind, boolean initialized, boolean enumerable) {
            this(value, "var".equals(kind), "const".equals(kind), initialized, enumerable, true, false);
        }

        Binding(JsValue value, boolean isVar, boolean isConst, boolean initialized, boolean enumerable,
                boolean writable, boolean configurable) {
            this.value = value;
            this.isVar = isVar;
            this.isConst = isConst;
            this.initialized = initialized;
            this.enumerable = enumerable;
            this.writable = writable;
            this.configurable = configurable;
        }

        static Binding var(JsValue value, boolean enumerable, boolean writable, boolean configurable) {
            return new Binding(value, true, false, true, enumerable, writable, configurable);
        }

        static Binding lazyVar(LazyBuiltin holder) {
            final var binding = var(null, false, true, true);
            binding.lazy = holder;
            return binding;
        }

        JsValue value() {
            final var holder = lazy;
            if (holder != null) {
                value = holder.get();
                lazy = null;
            }
            return value;
        }

        void assign(JsValue next) {
            value = next;
            lazy = null;
        }

        Binding withFlags(JsValue replacement, boolean enumerable, boolean writable, boolean configurable) {
            final var next = new Binding(replacement == null ? value : replacement, isVar, isConst, true, enumerable,
                    writable, configurable);
            next.lazy = replacement == null ? lazy : null;
            return next;
        }
    }

    private final boolean ordered;
    private String[] names = new String[2];
    private Binding[] slots = new Binding[2];
    private int size;
    private Map<String, Binding> map;

    BindingTable(boolean ordered) {
        this.ordered = ordered;
    }

    private int indexOf(String name) {
        for (var i = 0; i < size; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    Binding get(String name) {
        if (map != null) {
            return map.get(name);
        }
        final var index = indexOf(name);
        return index < 0 ? null : slots[index];
    }

    boolean containsKey(String name) {
        return get(name) != null;
    }

    void put(String name, Binding binding) {
        if (map != null) {
            map.put(name, binding);
            return;
        }
        final var index = indexOf(name);
        if (index >= 0) {
            slots[index] = binding;
            return;
        }
        append(name, binding);
    }

    Binding putIfAbsent(String name, Binding binding) {
        final var existing = get(name);
        if (existing != null) {
            return existing;
        }
        if (map != null) {
            map.put(name, binding);
        } else {
            append(name, binding);
        }
        return binding;
    }

    private void append(String name, Binding binding) {
        if (size == SMALL) {
            final Map<String, Binding> grown = ordered ? new LinkedHashMap<>() : new HashMap<>();
            for (var i = 0; i < size; i++) {
                grown.put(names[i], slots[i]);
            }
            grown.put(name, binding);
            map = grown;
            names = null;
            slots = null;
            size = 0;
            return;
        }
        if (size == names.length) {
            names = Arrays.copyOf(names, Math.min(SMALL, size * 2));
            slots = Arrays.copyOf(slots, names.length);
        }
        names[size] = name;
        slots[size] = binding;
        size++;
    }

    void remove(String name) {
        if (map != null) {
            map.remove(name);
            return;
        }
        final var index = indexOf(name);
        if (index < 0) {
            return;
        }
        final var tail = size - index - 1;
        System.arraycopy(names, index + 1, names, index, tail);
        System.arraycopy(slots, index + 1, slots, index, tail);
        size--;
        names[size] = null;
        slots[size] = null;
    }

    void forEach(BiConsumer<String, Binding> action) {
        if (map != null) {
            map.forEach(action);
            return;
        }
        for (var i = 0; i < size; i++) {
            action.accept(names[i], slots[i]);
        }
    }
}

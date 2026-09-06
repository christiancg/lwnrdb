package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;

// An arguments exotic object: every own property lives in the PropertyTable, with the spec's
// [[ParameterMap]] layered over the canonical index keys. A mapped index reads and writes the
// activation binding of its formal parameter; defining it as an accessor or as non-writable, or
// deleting it, detaches the mapping so the ordinary property takes over from then on.
public final class JsArguments extends JsValue {
    private static final String LENGTH = "length";
    private static final PropertyFlags ARGUMENT_FLAGS = new PropertyFlags(true, true, true);
    private static final PropertyFlags LENGTH_FLAGS = new PropertyFlags(true, false, true);

    private final PropertyTable table = new PropertyTable();
    private final List<String> mappedNames;
    private final Environment env;

    public JsArguments(List<JsValue> args, List<String> mappedNames, Environment env) {
        this.mappedNames = parameterMap(args.size(), mappedNames);
        this.env = env;
        for (var i = 0; i < args.size(); i++) {
            final var key = Integer.toString(i);
            table.defineValue(key, args.get(i));
            table.setFlags(key, ARGUMENT_FLAGS);
        }
        table.defineValue(LENGTH, new JsNumber(args.size()));
        table.setFlags(LENGTH, LENGTH_FLAGS);
    }

    // A formal parameter that was never passed has no argument slot to map, so the map stops at the
    // number of arguments actually supplied.
    private static List<String> parameterMap(int count, List<String> names) {
        if (names == null) {
            return null;
        }
        final var map = new ArrayList<String>();
        for (var i = 0; i < Math.min(count, names.size()); i++) {
            map.add(names.get(i));
        }
        return map;
    }

    public int length() {
        final var value = table.get(LENGTH);
        return value instanceof JsNumber number && number.getValue() > 0 ? (int) number.getValue() : 0;
    }

    public JsValue get(int index) {
        if (isMapped(index)) {
            return env.get(mappedNames.get(index));
        }
        final var key = Integer.toString(index);
        return index < 0 || !table.has(key) ? JsUndefined.getInstance() : table.get(key);
    }

    public boolean set(int index, JsValue value) {
        return index >= 0 && setProperty(Integer.toString(index), value);
    }

    public boolean setProperty(String key, JsValue value) {
        final var index = mappedIndex(new JsString(key));
        if (index >= 0) {
            env.assign(mappedNames.get(index), value);
        }
        return table.set(key, value);
    }

    public List<JsValue> snapshot() {
        final var result = new ArrayList<JsValue>();
        for (var i = 0; i < length(); i++) {
            result.add(get(i));
        }
        return result;
    }

    public List<String> enumerablePropertyKeys() {
        final var keys = new ArrayList<String>();
        for (final var key : table.keys()) {
            if (table.isEnumerable(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private boolean isMapped(int index) {
        return env != null && mappedNames != null && index >= 0 && index < mappedNames.size()
                && mappedNames.get(index) != null;
    }

    private int mappedIndex(JsValue key) {
        if (key instanceof JsSymbol) {
            return -1;
        }
        final var index = InterpreterUtils.arrayIndex(OrdinaryProperties.keyName(key));
        return index != null && isMapped(index) ? index : -1;
    }

    private void unmap(int index) {
        if (index >= 0 && mappedNames != null && index < mappedNames.size()) {
            mappedNames.set(index, null);
        }
    }

    @Override
    public PropertyTable ownProperties() {
        return table;
    }

    @Override
    public PropertyDescriptor getOwnProperty(JsValue key) {
        final var descriptor = super.getOwnProperty(key);
        final var index = mappedIndex(key);
        if (descriptor == null || index < 0) {
            return descriptor;
        }
        return new PropertyDescriptor(env.get(mappedNames.get(index)), null, null, descriptor.writable(),
                descriptor.enumerable(), descriptor.configurable());
    }

    @Override
    public boolean defineOwnProperty(JsValue key, PropertyDescriptor descriptor) {
        final var index = mappedIndex(key);
        if (index < 0) {
            return super.defineOwnProperty(key, descriptor);
        }
        final var name = mappedNames.get(index);
        super.defineOwnProperty(key, ordinaryPart(descriptor, name));
        if (descriptor.isAccessorDescriptor()) {
            unmap(index);
            return true;
        }
        if (descriptor.value() != null) {
            env.assign(name, descriptor.value());
        }
        if (Boolean.FALSE.equals(descriptor.writable())) {
            unmap(index);
        }
        return true;
    }

    // A redefine that only clears [[Writable]] leaves the ordinary property holding whatever the
    // binding says right now, since the mapping is about to be detached and cannot supply it later.
    private PropertyDescriptor ordinaryPart(PropertyDescriptor descriptor, String name) {
        if (descriptor.isAccessorDescriptor() || descriptor.value() != null
                || !Boolean.FALSE.equals(descriptor.writable())) {
            return descriptor;
        }
        return new PropertyDescriptor(env.get(name), null, null, descriptor.writable(), descriptor.enumerable(),
                descriptor.configurable());
    }

    @Override
    public boolean deleteOwnProperty(JsValue key) {
        final var index = mappedIndex(key);
        final var deleted = super.deleteOwnProperty(key);
        if (deleted) {
            unmap(index);
        }
        return deleted;
    }
}

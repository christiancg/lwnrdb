package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;

final class JsArrayProperties {
    private final JsArray jsArray;

    JsArrayProperties(JsArray jsArray) {
        this.jsArray = jsArray;
    }

    java.util.Set<String> namedPropertyKeys() {
        return jsArray.table.keys();
    }

    public List<JsValue> ownPropertyKeys() {
        final var keys = new ArrayList<JsValue>();
        for (var i = 0; i < jsArray.elements.size(); i++) {
            if (!jsArray.isHole(i)) {
                keys.add(new JsString(Integer.toString(i)));
            }
        }
        if (jsArray.sparseValues != null && !jsArray.sparseValues.isEmpty()) {
            final var sparseKeys = new ArrayList<>(jsArray.sparseValues.keySet());
            sparseKeys.sort(null);
            for (final var sparseKey : sparseKeys) {
                keys.add(new JsString(Integer.toString(sparseKey)));
            }
        }
        keys.add(new JsString("length"));
        for (final var key : jsArray.table.keys()) {
            keys.add(new JsString(key));
        }
        keys.addAll(jsArray.table.symbolKeys());
        return keys;
    }

    public PropertyDescriptor getOwnProperty(JsValue key) {
        if (key instanceof JsSymbol) {
            return jsArray.superGetOwnProperty(key);
        }
        final var name = OrdinaryProperties.keyName(key);
        if ("length".equals(name)) {
            return PropertyDescriptor.data(new JsNumber(jsArray.length), jsArray.lengthFlags);
        }
        final var index = InterpreterUtils.arrayIndex(name);
        if (index != null) {
            if (jsArray.hasIndexAccessor(index)) {
                return PropertyDescriptor.accessor(jsArray.getIndexAccessorGetter(index),
                        jsArray.getIndexAccessorSetter(index), jsArray.getIndexFlags(index));
            }
            return jsArray.isHole(index)
                    ? null
                    : PropertyDescriptor.data(jsArray.get(index), jsArray.getIndexFlags(index));
        }
        if (jsArray.hasPropAccessor(name)) {
            return PropertyDescriptor.accessor(jsArray.getPropAccessorGetter(name), jsArray.getPropAccessorSetter(name),
                    jsArray.getPropFlags(name));
        }
        return jsArray.table.has(name)
                ? PropertyDescriptor.data(jsArray.table.get(name), jsArray.getPropFlags(name))
                : null;
    }

    public boolean defineOwnProperty(JsValue key, PropertyDescriptor descriptor) {
        if (key instanceof JsSymbol) {
            return jsArray.superDefineOwnProperty(key, descriptor);
        }
        final var name = OrdinaryProperties.keyName(key);
        if ("length".equals(name)) {
            if (descriptor.isAccessorDescriptor()) {
                throw OrdinaryProperties.redefineError(name);
            }
            jsArray.lengths.defineLengthFrom(name, descriptor);
            return true;
        }
        final var wideIndex = InterpreterUtils.canonicalArrayIndexWide(name);
        if (wideIndex == null) {
            return jsArray.superDefineOwnProperty(key, descriptor);
        }
        if (wideIndex >= jsArray.length && !jsArray.lengthFlags.writable()) {
            throw new TypeErrorException("Cannot define property " + name + ", jsArray.length is not writable");
        }
        if (wideIndex <= Integer.MAX_VALUE) {
            OrdinaryProperties.validateAndApply(indexSlot(wideIndex.intValue()), jsArray.table.isExtensible(), name,
                    descriptor);
        } else {
            jsArray.superDefineOwnProperty(key, descriptor);
        }
        if (wideIndex + 1 > jsArray.length) {
            jsArray.length = wideIndex + 1;
        }
        return true;
    }

    OrdinaryProperties.Slot indexSlot(int index) {
        return new OrdinaryProperties.Slot() {
            @Override
            public boolean exists() {
                return ownsIndex(index);
            }

            @Override
            public boolean hasAccessor() {
                return jsArray.hasIndexAccessor(index);
            }

            @Override
            public JsObject.PropertyFlags flags() {
                return jsArray.getIndexFlags(index);
            }

            @Override
            public void setFlags(JsObject.PropertyFlags flags) {
                jsArray.setIndexFlags(index, flags);
            }

            @Override
            public JsValue value() {
                return jsArray.get(index);
            }

            @Override
            public boolean hasValue() {
                return !jsArray.hasIndexAccessor(index) && !jsArray.isHole(index);
            }

            @Override
            public void defineValue(JsValue value) {
                jsArray.defineIndexValue(index, value);
            }

            @Override
            public void removeValue() {
                jsArray.defineIndexValue(index, JsUndefined.getInstance());
            }

            @Override
            public JsValue getter() {
                return jsArray.getIndexAccessorGetter(index);
            }

            @Override
            public JsValue setter() {
                return jsArray.getIndexAccessorSetter(index);
            }

            @Override
            public void defineAccessor(JsValue getter, JsValue setter) {
                jsArray.defineIndexAccessor(index, getter, setter);
            }

            @Override
            public void clearGetter() {
                if (jsArray.indexGetters != null) {
                    jsArray.indexGetters.remove(index);
                }
            }

            @Override
            public void clearSetter() {
                if (jsArray.indexSetters != null) {
                    jsArray.indexSetters.remove(index);
                }
            }

            @Override
            public void clearAccessor() {
                jsArray.clearIndexAccessor(index);
            }
        };
    }

    boolean ownsIndex(int index) {
        return (index < jsArray.length && !jsArray.isHole(index)) || jsArray.hasIndexAccessor(index);
    }

    public boolean deleteOwnProperty(JsValue key) {
        if (key instanceof JsSymbol) {
            return jsArray.superDeleteOwnProperty(key);
        }
        final var name = OrdinaryProperties.keyName(key);
        if ("length".equals(name)) {
            return jsArray.lengthFlags.configurable();
        }
        final var index = InterpreterUtils.arrayIndex(name);
        if (index != null) {
            if (jsArray.isHole(index)) {
                return true;
            }
            if (!jsArray.getIndexFlags(index).configurable()) {
                return false;
            }
            jsArray.clearIndexToHole(index);
            return true;
        }
        if (!jsArray.table.has(name) && !jsArray.table.hasAccessor(name)) {
            return true;
        }
        if (!jsArray.getPropFlags(name).configurable()) {
            return false;
        }
        return jsArray.deleteProperty(name);
    }
}

package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.Collections;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;

final class JsArrayLength {
    private final JsArray jsArray;

    JsArrayLength(JsArray jsArray) {
        this.jsArray = jsArray;
    }

    long length() {
        return jsArray.length;
    }

    boolean setLength(long newLength) {
        if (jsArray.frozen || (jsArray.sealed && newLength != jsArray.length)) {
            return false;
        }
        if (!jsArray.table.isExtensible() && newLength > jsArray.length) {
            return false;
        }
        if (!jsArray.lengthFlags.writable()) {
            return false;
        }
        return truncateTo(newLength);
    }

    boolean truncateTo(long newLength) {
        if (newLength < jsArray.length) {
            if (!removeWideTailDown(newLength)) {
                return false;
            }
            if (!removeSparseTailDown(newLength)) {
                return false;
            }
            if (!removeDenseTailDown(newLength)) {
                return false;
            }
        }
        jsArray.length = newLength;
        if (jsArray.length <= JsArray.MAX_DENSE_LENGTH) {
            padTo((int) jsArray.length);
        }
        return true;
    }

    boolean removeWideTailDown(long newLength) {
        final var wideKeys = new ArrayList<Long>();
        for (final var key : jsArray.table.keys()) {
            final var wide = InterpreterUtils.canonicalArrayIndexWide(key);
            if (wide != null && wide > Integer.MAX_VALUE) {
                wideKeys.add(wide);
            }
        }
        if (wideKeys.isEmpty()) {
            return true;
        }
        wideKeys.sort(Collections.reverseOrder());
        for (final var wideKey : wideKeys) {
            if (wideKey < newLength) {
                continue;
            }
            final var name = Long.toString(wideKey);
            if (!jsArray.getPropFlags(name).configurable()) {
                jsArray.length = wideKey + 1L;
                return false;
            }
            jsArray.deleteProperty(name);
        }
        return true;
    }

    boolean removeSparseTailDown(long newLength) {
        if (jsArray.sparseValues == null || jsArray.sparseValues.isEmpty()) {
            return true;
        }
        final var keys = new ArrayList<>(jsArray.sparseValues.keySet());
        keys.sort(Collections.reverseOrder());
        for (final var key : keys) {
            if (key < newLength) {
                continue;
            }
            if (!jsArray.getIndexFlags(key).configurable()) {
                jsArray.length = key + 1L;
                return false;
            }
            jsArray.sparseValues.remove(key);
            jsArray.clearIndexAccessor(key);
            if (jsArray.indexFlags != null) {
                jsArray.indexFlags.remove(key);
            }
        }
        return true;
    }

    boolean removeDenseTailDown(long newLength) {
        while (jsArray.elements.size() > newLength) {
            final var last = jsArray.elements.size() - 1;
            if (jsArray.ownsIndex(last) && !jsArray.getIndexFlags(last).configurable()) {
                jsArray.length = jsArray.elements.size();
                return false;
            }
            jsArray.clearIndexAccessor(last);
            if (jsArray.indexFlags != null) {
                jsArray.indexFlags.remove(last);
            }
            jsArray.elements.removeLast();
        }
        return true;
    }

    void setLengthWritable(boolean writable) {
        jsArray.lengthFlags = new JsObject.PropertyFlags(writable, jsArray.lengthFlags.enumerable(), false);
    }

    boolean defineLength(long newLength) {
        return truncateTo(newLength);
    }

    void padToIndex(int index) {
        padTo(index + 1);
    }

    void padTo(int denseLength) {
        while (jsArray.elements.size() < denseLength) {
            jsArray.elements.add(JsArray.HOLE);
        }
    }

    static void withLengthCoercionOps(InterpreterOps ops, Runnable action) {
        final var previous = JsArray.LENGTH_COERCION_OPS.get();
        JsArray.LENGTH_COERCION_OPS.set(ops);
        try {
            action.run();
        } finally {
            if (previous == null) {
                JsArray.LENGTH_COERCION_OPS.remove();
            } else {
                JsArray.LENGTH_COERCION_OPS.set(previous);
            }
        }
    }

    void defineLengthFrom(String key, PropertyDescriptor descriptor) {
        final var newLength = descriptor.value() == null ? null : requireArrayLength(descriptor.value());
        if (!jsArray.lengthFlags.configurable() && Boolean.TRUE.equals(descriptor.configurable())) {
            throw OrdinaryProperties.redefineError(key);
        }
        if (!jsArray.lengthFlags.configurable() && descriptor.enumerable() != null
                && descriptor.enumerable() != jsArray.lengthFlags.enumerable()) {
            throw OrdinaryProperties.redefineError(key);
        }
        final var writable = descriptor.writableOr(jsArray.lengthFlags.writable());
        if (newLength == null) {
            if (!jsArray.lengthFlags.writable() && writable) {
                throw OrdinaryProperties.redefineError(key);
            }
            setLengthWritable(writable);
            return;
        }
        if (!jsArray.lengthFlags.writable()
                && (Boolean.TRUE.equals(descriptor.writable()) || newLength != jsArray.length)) {
            throw OrdinaryProperties.redefineError(key);
        }
        final var truncated = defineLength(newLength);
        setLengthWritable(writable);
        if (!truncated) {
            throw new TypeErrorException("Cannot redefine property: length");
        }
    }

    static long requireArrayLength(JsValue value) {
        final var ops = JsArray.LENGTH_COERCION_OPS.get();
        final var newLen = NumberFormatter.toUint32(JsCoercion.toNumber(value, ops));
        final var numberLen = JsCoercion.toNumber(value, ops);
        if (newLen != numberLen) {
            throw new RangeErrorException("Invalid array length");
        }
        return newLen;
    }
}

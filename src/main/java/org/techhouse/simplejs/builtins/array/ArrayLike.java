package org.techhouse.simplejs.builtins.array;

import static org.techhouse.simplejs.builtins.ArrayBuiltins.LENGTH;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.key;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.toLength;

import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public record ArrayLike(JsValue value, InterpreterOps ops) {
    public JsArray dense() {
        return value instanceof JsArray array && !array.hasAnyIndexAccessor() ? array : null;
    }

    public long length() {
        return toLength(getKey(LENGTH), ops);
    }

    public JsValue getKey(JsValue key) {
        return ops == null ? JsUndefined.getInstance() : ops.getMember(value, key);
    }

    public JsValue get(long index) {
        final var dense = dense();
        if (dense != null && index >= 0 && index <= Integer.MAX_VALUE && index < dense.length()
                && !dense.isHole((int) index)) {
            return dense.get((int) index);
        }
        final var element = getKey(key(index));
        return element == JsUndefined.getHole() ? JsUndefined.getInstance() : element;
    }

    public boolean has(long index) {
        final var dense = dense();
        if (dense != null && index >= 0 && index <= Integer.MAX_VALUE && index < dense.length()
                && !dense.isHole((int) index)) {
            return true;
        }
        if (ops == null) {
            return false;
        }
        final var key = key(index);
        if (supportsHas(value) && ops.has(value, key)) {
            return true;
        }
        return needsReadFallback(value) && !(ops.getMember(value, key) instanceof JsUndefined);
    }

    public static boolean needsReadFallback(JsValue value) {
        return !supportsHas(value) || (value instanceof JsObject object && object.getPrimitive() != null);
    }

    public void set(long index, JsValue element) {
        if (!trySet(index, element)) {
            throw new TypeErrorException("Cannot assign to read only property '" + index + "' of the receiver");
        }
    }

    public boolean trySet(long index, JsValue element) {
        final var inherited = inheritedIndexAccessor(index);
        if (inherited != null) {
            final var setter = inherited.get("set");
            if (!InterpreterUtils.isCallable(setter)) {
                return false;
            }
            ops.call(setter, value, List.of(element));
            return true;
        }
        final var dense = dense();
        if (dense != null && index <= Integer.MAX_VALUE) {
            return dense.set((int) index, element);
        }
        if (ops == null) {
            return true;
        }
        if (wrappedString() instanceof JsString string) {
            return index >= string.getValue().length();
        }
        return ops.setMember(value, key(index), element);
    }

    public JsObject inheritedIndexAccessor(long index) {
        if (ops == null || !(value instanceof JsArray array)) {
            return null;
        }
        final var propertyKey = key(index);
        if (!ops.has(value, propertyKey) || array.getOwnProperty(propertyKey) != null) {
            return null;
        }
        return inheritedAccessor(propertyKey);
    }

    public JsObject inheritedAccessor(JsString propertyKey) {
        JsObject accessor = null;
        var proto = ops.getPrototypeOf(value);
        while (accessor == null && InterpreterUtils.isObjectLike(proto)) {
            final var descriptor = ops.getOwnPropertyDescriptor(proto, propertyKey);
            if (descriptor instanceof JsObject fields) {
                accessor = fields.has("get") || fields.has("set") ? fields : null;
                proto = JsNull.getInstance();
            } else {
                proto = ops.getPrototypeOf(proto);
            }
        }
        return accessor;
    }

    public void setLength(long length) {
        if (wrappedString() != null || (ops != null && !ops.setMember(value, LENGTH, new JsNumber(length)))) {
            throw new TypeErrorException("Cannot assign to read only property 'length' of the receiver");
        }
    }

    public JsValue wrappedString() {
        if (value instanceof JsString) {
            return value;
        }
        return value instanceof JsObject object && object.getPrimitive() instanceof JsString
                ? object.getPrimitive()
                : null;
    }

    public void delete(long index) {
        if (ops == null) {
            final var dense = dense();
            if (dense != null && index >= 0 && index <= Integer.MAX_VALUE && index < dense.length()) {
                dense.clearIndexToHole((int) index);
            }
            return;
        }
        if (!ops.deleteMember(value, key(index))) {
            throw new TypeErrorException("Cannot delete property '" + index + "' of the receiver");
        }
    }

    public static boolean supportsHas(JsValue value) {
        return value instanceof JsObject || value instanceof JsProxy || value instanceof JsArray
                || value instanceof JsClass || value instanceof JsFunction || value instanceof JsNativeFunction
                || value instanceof JsArguments || value instanceof JsTypedArray;
    }
}

package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.arrayIndex;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.orUndefined;

import java.util.List;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.builtins.IteratorBuiltins;
import org.techhouse.simplejs.builtins.RegexBuiltins;
import org.techhouse.simplejs.builtins.SymbolBuiltins;
import org.techhouse.simplejs.builtins.typedarray.ArrayBufferBuiltins;
import org.techhouse.simplejs.builtins.typedarray.DataViewBuiltins;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

final class BuiltinMemberLookup {
    private final Interpreter interp;
    private MemberEvaluator members;

    BuiltinMemberLookup(Interpreter interp) {
        this.interp = interp;
    }

    void bind(MemberEvaluator members) {
        this.members = members;
    }

    JsValue intrinsicMember(JsValue target, String key) {
        final var table = target.ownProperties();
        if (table != null) {
            if (table.hasAccessor(key)) {
                final var getter = table.getAccessorGetter(key);
                return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, target, List.of());
            }
            if (table.has(key)) {
                return table.get(key);
            }
        }
        return orUndefined(members.chainMember(members.protoChainStart(target), key, target));
    }

    JsValue functionMember(JsValue function, String key) {
        final var table = function.ownProperties();
        if (table.hasAccessor(key)) {
            final var getter = table.getAccessorGetter(key);
            return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, function, List.of());
        }
        var metadataDeleted = false;
        switch (function) {
            case JsCallableProperties callable when callable.hasProperty(key) -> {
                return callable.getProperty(key);
            }
            case JsFunction fn when "prototype".equals(key) && (fn.isConstructor() || fn.isGenerator()) -> {
                return fn.getPrototype();
            }
            case JsNativeFunction nf when "prototype".equals(key) -> {
                return orUndefined(nf.getPrototype());
            }
            case JsCallableProperties callable when callable.isMetadataDeleted(key) -> metadataDeleted = true;
            default -> {
            }
        }
        final var metadata = metadataDeleted ? null : FunctionProtoBuiltins.metadata(function, key);
        if (metadata != null) {
            return metadata;
        }
        if (function instanceof JsNativeFunction nf && nf.getOwnProto() != null) {
            final var inherited = members.chainMember(nf.getOwnProto(), key, function);
            if (inherited != null) {
                return inherited;
            }
        }
        return intrinsicMember(function, key);
    }

    JsValue mapMember(JsMap map, String key) {
        if ("size".equals(key)) {
            return new JsNumber(map.size());
        }
        return intrinsicMember(map, key);
    }

    JsValue jsSetMember(JsSet set, String key) {
        if ("size".equals(key)) {
            return new JsNumber(set.size());
        }
        return intrinsicMember(set, key);
    }

    JsValue dateMember(JsDate date, String key) {
        return intrinsicMember(date, key);
    }

    JsValue bufferMember(JsArrayBuffer buffer, String key) {
        if (ArrayBufferBuiltins.isBufferAccessor(key)) {
            return orUndefined(ArrayBufferBuiltins.bufferMethod(buffer, key));
        }
        return intrinsicMember(buffer, key);
    }

    JsValue dataViewMember(JsDataView view, String key) {
        if (DataViewBuiltins.isViewAccessor(key)) {
            return orUndefined(DataViewBuiltins.dataViewMethod(view, key));
        }
        return intrinsicMember(view, key);
    }

    JsValue typedArrayMember(JsTypedArray typed, String key) {
        final var table = typed.ownProperties();
        if (!table.has(key) && !table.hasAccessor(key)) {
            switch (key) {
                case "length" -> {
                    return new JsNumber(typed.length());
                }
                case "byteLength" -> {
                    return new JsNumber(typed.byteLength());
                }
                case "byteOffset" -> {
                    return new JsNumber(typed.byteOffset());
                }
                case "buffer" -> {
                    return typed.getBuffer();
                }
                case "BYTES_PER_ELEMENT" -> {
                    return new JsNumber(typed.kind().bytesPerElement());
                }
                default -> {
                }
            }
        }
        final var index = arrayIndex(key);
        if (index != null) {
            return typed.getElement(index);
        }
        if (InterpreterUtils.isCanonicalNumericIndexString(key)) {
            return JsUndefined.getInstance();
        }
        if (table.hasAccessor(key)) {
            final var getter = table.getAccessorGetter(key);
            return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, typed, List.of());
        }
        return table.has(key) ? table.get(key) : intrinsicMember(typed, key);
    }

    JsValue numberMember(JsNumber number, String key) {
        return intrinsicMember(number, key);
    }

    JsValue symbolMember(JsSymbol symbol, String key) {
        final var property = SymbolBuiltins.getProperty(symbol, key);
        if (property != null) {
            return property;
        }
        return intrinsicMember(symbol, key);
    }

    JsValue getStringMember(JsString string, String key) {
        if ("length".equals(key)) {
            return new JsNumber(string.getValue().length());
        }
        final var index = arrayIndex(key);
        if (index != null) {
            return index < string.getValue().length()
                    ? new JsString(String.valueOf(string.getValue().charAt(index)))
                    : JsUndefined.getInstance();
        }
        return intrinsicMember(string, key);
    }

    JsValue generatorMethod(JsGenerator generator, String key) {
        final var intrinsic = intrinsicMember(generator, key);
        if (!(intrinsic instanceof JsUndefined) || !IteratorBuiltins.isHelperName(key)) {
            return intrinsic;
        }
        return IteratorBuiltins.helper(interp.ops(), key, interp.intrinsics().objectProto);
    }

    JsValue regExpMember(JsRegExp regexp, String key) {
        final var table = regexp.ownProperties();
        if (table.hasAccessor(key)) {
            final var getter = table.getAccessorGetter(key);
            return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, regexp, List.of());
        }
        if (table.has(key)) {
            return table.get(key);
        }
        if (RegexBuiltins.isAccessor(key)) {
            return orUndefined(RegexBuiltins.getMethod(regexp, key));
        }
        return intrinsicMember(regexp, key);
    }

    JsValue promiseMethod(JsPromise promise, String key) {
        final var table = promise.ownProperties();
        if (table.hasAccessor(key)) {
            final var getter = table.getAccessorGetter(key);
            return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, promise, List.of());
        }
        return table.has(key) ? table.get(key) : intrinsicMember(promise, key);
    }

}

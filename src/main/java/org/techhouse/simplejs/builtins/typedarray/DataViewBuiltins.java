package org.techhouse.simplejs.builtins.typedarray;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTarget;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.VIEW_ACCESSORS;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.VIEW_ACCESSOR_NAMES;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.boolArg;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.observePrototype;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.wrapWithObservedPrototype;
import static org.techhouse.simplejs.builtins.typedarray.ArrayBufferBuiltins.toIndex;
import static org.techhouse.simplejs.builtins.typedarray.ArrayBufferBuiltins.toIndexArg;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.NumberBuiltins;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class DataViewBuiltins {
    public static List<String> viewAccessorNames() {
        return VIEW_ACCESSOR_NAMES;
    }

    public static List<String> viewNames() {
        final var elementTypes = List.of("Int8", "Uint8", "Int16", "Uint16", "Int32", "Uint32", "Float16", "Float32",
                "Float64", "BigInt64", "BigUint64");
        final var names = new ArrayList<String>();
        for (final var type : elementTypes) {
            names.add("get" + type);
            names.add("set" + type);
        }
        return List.copyOf(names);
    }

    public static boolean isViewAccessor(String name) {
        return VIEW_ACCESSORS.contains(name);
    }

    public static JsNativeFunction dataView(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("DataView", (thisArg, args) -> {
            requireNewTarget("DataView", thisArg);
            return constructDataView(args, ops);
        });
        ctor.setLength(1);
        return ctor;
    }

    public static JsValue constructDataView(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsArrayBuffer buffer)) {
            throw new TypeErrorException("First argument to DataView constructor must be an ArrayBuffer");
        }
        final var byteOffset = (int) toIndex(arg(args, 1), "DataView byteOffset", ops);
        final var explicitLength = args.size() > 2 && !(args.get(2) instanceof JsUndefined);
        final var requested = explicitLength ? (int) toIndex(args.get(2), "DataView byteLength", ops) : 0;
        if (buffer.isDetached()) {
            throw new TypeErrorException("Cannot construct a DataView over a detached ArrayBuffer");
        }
        if (byteOffset > buffer.byteLength()) {
            throw new RangeErrorException("Start offset is outside the bounds of the buffer");
        }
        final var observedProto = observePrototype(ops);
        if (buffer.isDetached()) {
            throw new TypeErrorException("Cannot construct a DataView over a detached ArrayBuffer");
        }
        if (byteOffset > buffer.byteLength()) {
            throw new RangeErrorException("Start offset is outside the bounds of the buffer");
        }
        final JsValue view;
        if (!explicitLength) {
            view = new JsDataView(buffer, byteOffset, buffer.byteLength() - byteOffset, buffer.isResizable());
        } else {
            if (byteOffset + requested > buffer.byteLength()) {
                throw new RangeErrorException("Invalid DataView length");
            }
            view = new JsDataView(buffer, byteOffset, requested);
        }
        return wrapWithObservedPrototype(view, observedProto, ops);
    }

    public static JsValue dataViewMethod(JsDataView view, String name) {
        return dataViewMethod(view, name, null);
    }

    public static JsValue dataViewMethod(JsDataView view, String name, InterpreterOps ops) {
        return switch (name) {
            case "buffer" -> view.getBuffer();
            case "byteLength" -> new JsNumber(view.byteLength());
            case "byteOffset" -> new JsNumber(view.byteOffset());
            default -> dataViewAccessor(view, name, ops);
        };
    }

    public static JsValue dataViewAccessor(JsDataView view, String name, InterpreterOps ops) {
        if (name.startsWith("getBig")) {
            return new JsNativeFunction(name, (_, args) -> new JsBigInt(
                    view.getBigInt(name.contains("Uint"), toIndexArg(args, ops), boolArg(args, 1))));
        }
        if (name.startsWith("setBig")) {
            return new JsNativeFunction(name, (_, args) -> {
                final var offset = toIndexArg(args, ops);
                final var value = NumberBuiltins.toBigIntValue(arg(args, 1), ops).getValue();
                view.setBigInt(offset, value, boolArg(args, 2));
                return JsUndefined.getInstance();
            });
        }
        if (name.startsWith("get")) {
            return new JsNativeFunction(name,
                    (_, args) -> new JsNumber(view.getNumber(name, toIndexArg(args, ops), boolArg(args, 1))));
        }
        if (name.startsWith("set")) {
            return new JsNativeFunction(name, (_, args) -> {
                final var offset = toIndexArg(args, ops);
                final var value = JsCoercion.toNumber(arg(args, 1), ops);
                view.setNumber(name, offset, value, boolArg(args, 2));
                return JsUndefined.getInstance();
            });
        }
        return null;
    }

    private DataViewBuiltins() {
    }
}

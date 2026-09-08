package org.techhouse.simplejs.builtins.typedarray;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTarget;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.BUFFER_ACCESSORS;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.BUFFER_ACCESSOR_NAMES;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.intArg;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.observePrototype;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.wrapWithObservedPrototype;
import static org.techhouse.simplejs.values.JsLimits.MAX_SAFE_INTEGER;

import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ArrayBufferBuiltins {
    public static List<String> bufferAccessorNames() {
        return BUFFER_ACCESSOR_NAMES;
    }

    public static boolean isBufferAccessor(String name) {
        return BUFFER_ACCESSORS.contains(name);
    }

    public static JsNativeFunction arrayBuffer(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("ArrayBuffer", (thisArg, args) -> {
            requireNewTarget("ArrayBuffer", thisArg);
            return constructArrayBuffer(args, ops);
        });
        ctor.setProperty("isView", new JsNativeFunction("isView", (_, args) -> JsBoolean.of(isView(arg(args, 0)))));
        return ctor;
    }

    public static boolean isView(JsValue value) {
        final var target = value instanceof JsObject wrapper ? wrapper.getPrimitive() : value;
        return target instanceof JsTypedArray || target instanceof JsDataView;
    }

    public static JsValue constructArrayBuffer(List<JsValue> args, InterpreterOps ops) {
        final var byteLength = toIndex(arg(args, 0), "ArrayBuffer length", ops);
        final var options = arg(args, 1);
        final var requestedMax = ops != null && InterpreterUtils.isObjectLike(options)
                ? ops.getMember(options, new JsString("maxByteLength"))
                : JsUndefined.getInstance();
        final var hasMaxByteLength = !(requestedMax instanceof JsUndefined);
        final var maxByteLength = hasMaxByteLength ? toIndex(requestedMax, "ArrayBuffer maxByteLength", ops) : 0;
        if (hasMaxByteLength && maxByteLength < byteLength) {
            throw new RangeErrorException("ArrayBuffer maxByteLength must be >= byteLength");
        }
        final var observedProto = observePrototype(ops);
        JsArrayBuffer.checkAllocation(byteLength);
        InterpreterOps.charge(ops, byteLength);
        final JsValue buffer;
        if (hasMaxByteLength) {
            JsArrayBuffer.checkAllocation(maxByteLength);
            InterpreterOps.charge(ops, maxByteLength);
            buffer = new JsArrayBuffer((int) byteLength, (int) maxByteLength, true);
        } else {
            buffer = new JsArrayBuffer((int) byteLength);
        }
        return wrapWithObservedPrototype(buffer, observedProto, ops);
    }

    public static JsValue bufferMethod(JsArrayBuffer buffer, String name) {
        return bufferMethod(buffer, name, null);
    }

    public static JsValue bufferMethod(JsArrayBuffer buffer, String name, InterpreterOps ops) {
        return switch (name) {
            case "byteLength" -> new JsNumber(buffer.byteLength());
            case "maxByteLength" -> new JsNumber(buffer.maxByteLength());
            case "resizable" -> JsBoolean.of(buffer.isResizable());
            case "detached" -> JsBoolean.of(buffer.isDetached());
            case "slice" -> new JsNativeFunction("slice", (_, args) -> {
                final var from = (int) intArg(args, 0, 0, ops);
                final var to = (int) intArg(args, 1, buffer.byteLength(), ops);
                InterpreterOps.charge(ops, Math.max(to - from, 0));
                final var sliced = buffer.slice(from, to);
                requireBufferSpecies(buffer, ops);
                return sliced;
            });
            case "resize" -> new JsNativeFunction("resize", (_, args) -> {
                final var resized = (int) toIndex(arg(args, 0), "ArrayBuffer length", ops);
                InterpreterOps.charge(ops, (long) resized + buffer.byteLength());
                buffer.resize(resized);
                return JsUndefined.getInstance();
            });
            case "transfer" -> new JsNativeFunction("transfer", (_, args) -> {
                InterpreterOps.charge(ops, args.isEmpty() ? (long) buffer.byteLength() * 3 : 0);
                return buffer.transfer(transferLength(args, ops), false);
            });
            case "transferToFixedLength" -> new JsNativeFunction("transferToFixedLength", (_, args) -> {
                InterpreterOps.charge(ops, args.isEmpty() ? (long) buffer.byteLength() * 3 : 0);
                return buffer.transfer(transferLength(args, ops), true);
            });
            default -> null;
        };
    }

    public static void requireBufferSpecies(JsArrayBuffer buffer, InterpreterOps ops) {
        if (ops == null) {
            return;
        }
        final var constructor = ops.getMember(buffer, new JsString("constructor"));
        if (!(constructor instanceof JsUndefined) && !InterpreterUtils.isObjectLike(constructor)) {
            throw new TypeErrorException("The constructor property is not an object");
        }
    }

    public static long toIndexArg(List<JsValue> args, InterpreterOps ops) {
        return toIndex(arg(args, 0), "DataView offset", ops);
    }

    public static int transferLength(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return -1;
        }
        final var length = toIndex(args.getFirst(), "ArrayBuffer length", ops);
        JsArrayBuffer.checkAllocation(length);
        InterpreterOps.charge(ops, length * 3);
        return (int) length;
    }

    public static long toIndex(JsValue value, String label, InterpreterOps ops) {
        if (value instanceof JsUndefined) {
            return 0;
        }
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number)) {
            return 0;
        }
        final var integer = number < 0 ? Math.ceil(number) : Math.floor(number);
        if (integer < 0 || integer > MAX_SAFE_INTEGER) {
            throw new RangeErrorException("Invalid " + label + ": " + number);
        }
        return (long) integer;
    }

    private ArrayBufferBuiltins() {
    }
}

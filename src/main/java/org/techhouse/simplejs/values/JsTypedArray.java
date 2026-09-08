package org.techhouse.simplejs.values;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.NumberBuiltins;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;

public final class JsTypedArray extends JsValue {
    private static final JsObject.PropertyFlags INDEX_FLAGS = new JsObject.PropertyFlags(true, true, true);

    private PropertyTable table;
    private InterpreterOps ops;
    private JsValue proto;

    public enum Kind {
        INT8("Int8Array", 1), UINT8("Uint8Array", 1), UINT8CLAMPED("Uint8ClampedArray", 1), INT16("Int16Array",
                2), UINT16("Uint16Array", 2), INT32("Int32Array", 4), UINT32("Uint32Array", 4), FLOAT16("Float16Array",
                        2), FLOAT32("Float32Array", 4), FLOAT64("Float64Array",
                                8), BIGINT64("BigInt64Array", 8), BIGUINT64("BigUint64Array", 8);

        private final String ctorName;
        private final int bytesPerElement;

        Kind(String ctorName, int bytesPerElement) {
            this.ctorName = ctorName;
            this.bytesPerElement = bytesPerElement;
        }

        public String ctorName() {
            return ctorName;
        }

        public int bytesPerElement() {
            return bytesPerElement;
        }
    }

    private final Kind kind;
    private final JsArrayBuffer buffer;
    private final int byteOffset;
    private final int length;
    private final boolean lengthTracking;

    public JsTypedArray(Kind kind, JsArrayBuffer buffer, int byteOffset, int length) {
        this(kind, buffer, byteOffset, length, false);
    }

    public JsTypedArray(Kind kind, JsArrayBuffer buffer, int byteOffset, int length, boolean lengthTracking) {
        this.kind = kind;
        this.buffer = buffer;
        this.byteOffset = byteOffset;
        this.length = length;
        this.lengthTracking = lengthTracking;
    }

    public JsTypedArray withOps(InterpreterOps ops) {
        this.ops = ops;
        return this;
    }

    public Kind kind() {
        return kind;
    }

    public JsArrayBuffer getBuffer() {
        return buffer;
    }

    public int byteOffset() {
        return isOutOfBounds() ? 0 : byteOffset;
    }

    public int rawByteOffset() {
        return byteOffset;
    }

    public boolean isLengthTracking() {
        return lengthTracking;
    }

    public boolean isOutOfBounds() {
        if (buffer.isDetached()) {
            return true;
        }
        final var bufferLength = buffer.byteLength();
        if (byteOffset > bufferLength) {
            return true;
        }
        if (lengthTracking) {
            return false;
        }
        return byteOffset + (long) length * kind.bytesPerElement > bufferLength;
    }

    public int byteLength() {
        return length() * kind.bytesPerElement;
    }

    public int length() {
        if (isOutOfBounds()) {
            return 0;
        }
        if (lengthTracking) {
            final var available = buffer.byteLength() - byteOffset;
            return available <= 0 ? 0 : available / kind.bytesPerElement;
        }
        return length;
    }

    private ByteBuffer view() {
        return ByteBuffer.wrap(buffer.getBytes()).order(ByteOrder.LITTLE_ENDIAN);
    }

    public JsValue getElement(int index) {
        final var pos = byteOffset + index * kind.bytesPerElement;
        if (index < 0 || index >= length() || pos + kind.bytesPerElement > buffer.byteLength()) {
            return JsUndefined.getInstance();
        }
        final var bb = view();
        return switch (kind) {
            case INT8 -> new JsNumber(bb.get(pos));
            case UINT8, UINT8CLAMPED -> new JsNumber(bb.get(pos) & 0xFF);
            case INT16 -> new JsNumber(bb.getShort(pos));
            case UINT16 -> new JsNumber(bb.getShort(pos) & 0xFFFF);
            case INT32 -> new JsNumber(bb.getInt(pos));
            case UINT32 -> new JsNumber(bb.getInt(pos) & 0xFFFFFFFFL);
            case FLOAT16 -> new JsNumber(Float.float16ToFloat(bb.getShort(pos)));
            case FLOAT32 -> new JsNumber(bb.getFloat(pos));
            case FLOAT64 -> new JsNumber(bb.getDouble(pos));
            case BIGINT64 -> new JsBigInt(BigInteger.valueOf(bb.getLong(pos)));
            case BIGUINT64 -> new JsBigInt(toUnsignedBig(bb.getLong(pos)));
        };
    }

    public void setElement(int index, JsValue value) {
        setElement(index, value, coercingOps(index));
    }

    private InterpreterOps coercingOps(int index) {
        return isValidIndex(index) || isOutOfBounds() ? ops : null;
    }

    public void setElement(int index, JsValue value, InterpreterOps ops) {
        if (ops == null && !isValidIndex(index)) {
            return;
        }
        final var primitive = elementPrimitive(value, ops);
        if (kind == Kind.BIGINT64 || kind == Kind.BIGUINT64) {
            final var big = reduceBig(primitive, ops);
            if (isValidIndex(index)) {
                view().putLong(byteOffset + index * kind.bytesPerElement, big.longValue());
            }
            return;
        }
        final var number = JsCoercion.toNumber(primitive, ops);
        if (!isValidIndex(index)) {
            return;
        }
        final var pos = byteOffset + index * kind.bytesPerElement;
        final var bb = view();
        switch (kind) {
            case INT8, UINT8 -> bb.put(pos, (byte) reduce(number, 8));
            case UINT8CLAMPED -> bb.put(pos, (byte) clamp(number));
            case INT16, UINT16 -> bb.putShort(pos, (short) reduce(number, 16));
            case INT32, UINT32 -> bb.putInt(pos, (int) reduce(number, 32));
            case FLOAT16 -> bb.putShort(pos, toFloat16(number));
            case FLOAT32 -> bb.putFloat(pos, (float) number);
            default -> bb.putDouble(pos, number);
        }
    }

    private boolean isValidIndex(int index) {
        final var pos = byteOffset + (long) index * kind.bytesPerElement;
        return index >= 0 && index < length() && pos + kind.bytesPerElement <= buffer.byteLength();
    }

    public static short toFloat16(double value) {
        return Float.floatToFloat16(toOddFloat(value));
    }

    private static float toOddFloat(double value) {
        final var narrowed = (float) value;
        if (narrowed == value || Double.isNaN(value) || Float.isInfinite(narrowed)
                || (Float.floatToRawIntBits(narrowed) & 1) != 0) {
            return narrowed;
        }
        return Math.nextAfter(narrowed, value);
    }

    private static JsValue elementPrimitive(JsValue value, InterpreterOps ops) {
        if (ops == null || value instanceof JsObject || value.ownProperties() == null) {
            return value;
        }
        final var exotic = ops.getMember(value, JsSymbol.TO_PRIMITIVE);
        if (isCallable(exotic)) {
            return requirePrimitive(ops.call(exotic, value, List.of(new JsString("number"))));
        }
        for (final var name : new String[]{"valueOf", "toString"}) {
            final var method = ops.getMember(value, new JsString(name));
            if (isCallable(method)) {
                final var result = ops.call(method, value, List.of());
                if (result.ownProperties() == null) {
                    return result;
                }
            }
        }
        throw new TypeErrorException("Cannot convert object to primitive value");
    }

    private static JsValue requirePrimitive(JsValue result) {
        if (result.ownProperties() != null) {
            throw new TypeErrorException("Cannot convert object to primitive value");
        }
        return result;
    }

    private static long reduce(double d, int bits) {
        return NumberFormatter.toUint32(d) & ((1L << bits) - 1);
    }

    private static int clamp(double d) {
        if (Double.isNaN(d) || d <= 0) {
            return 0;
        }
        if (d >= 255) {
            return 255;
        }
        return (int) Math.rint(d);
    }

    private static BigInteger reduceBig(JsValue value, InterpreterOps ops) {
        return NumberBuiltins.toBigIntValue(value, ops).getValue().mod(BigInteger.ONE.shiftLeft(64));
    }

    private static BigInteger toUnsignedBig(long raw) {
        return raw >= 0 ? BigInteger.valueOf(raw) : BigInteger.valueOf(raw).add(BigInteger.ONE.shiftLeft(64));
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }

    @Override
    public JsValue getProto() {
        return proto;
    }

    @Override
    public void setProto(JsValue proto) {
        this.proto = proto;
    }

    public static Double canonicalNumericIndex(String key) {
        if ("-0".equals(key)) {
            return -0.0;
        }
        final double parsed;
        try {
            parsed = Double.parseDouble(key);
        } catch (NumberFormatException ignored) {
            return null;
        }
        return NumberFormatter.toJsString(parsed).equals(key) ? parsed : null;
    }

    public boolean isValidIntegerIndex(double index) {
        if (isOutOfBounds() || index != Math.floor(index) || Double.isInfinite(index)) {
            return false;
        }
        if (index == 0 && Double.doubleToRawLongBits(index) != 0) {
            return false;
        }
        return index >= 0 && index < length();
    }

    @Override
    public List<JsValue> ownPropertyKeys() {
        final var keys = new ArrayList<JsValue>();
        final var length = length();
        for (var i = 0; i < length; i++) {
            keys.add(new JsString(Integer.toString(i)));
        }
        keys.addAll(super.ownPropertyKeys());
        return keys;
    }

    @Override
    public PropertyDescriptor getOwnProperty(JsValue key) {
        final var index = exoticIndex(key);
        if (index == null) {
            return super.getOwnProperty(key);
        }
        return isValidIntegerIndex(index)
                ? PropertyDescriptor.data(getElement((int) (double) index), INDEX_FLAGS)
                : null;
    }

    @Override
    public boolean defineOwnProperty(JsValue key, PropertyDescriptor descriptor) {
        final var index = exoticIndex(key);
        if (index == null) {
            return super.defineOwnProperty(key, descriptor);
        }
        if (!isValidIntegerIndex(index) || descriptor.isAccessorDescriptor()
                || Boolean.FALSE.equals(descriptor.configurable()) || Boolean.FALSE.equals(descriptor.enumerable())
                || Boolean.FALSE.equals(descriptor.writable())) {
            throw OrdinaryProperties.redefineError(OrdinaryProperties.keyName(key));
        }
        if (descriptor.value() != null) {
            setElement((int) (double) index, descriptor.value(), ops);
        }
        return true;
    }

    @Override
    public boolean deleteOwnProperty(JsValue key) {
        final var index = exoticIndex(key);
        return index == null ? super.deleteOwnProperty(key) : !isValidIntegerIndex(index);
    }

    public boolean setExoticIndex(JsValue key, JsValue value, JsValue receiver) {
        final var index = exoticIndex(key);
        if (index == null) {
            return false;
        }
        if (receiver == this) {
            setElement(elementSlot(index), value, ops);
            return true;
        }
        return !isValidIntegerIndex(index);
    }

    private static int elementSlot(double index) {
        if (index != Math.floor(index) || index < 0 || index > Integer.MAX_VALUE || Double.compare(index, -0.0) == 0) {
            return -1;
        }
        return (int) index;
    }

    public boolean hasCanonicalNumericIndex(JsValue key) {
        return exoticIndex(key) != null;
    }

    private static Double exoticIndex(JsValue key) {
        return key instanceof JsSymbol ? null : canonicalNumericIndex(OrdinaryProperties.keyName(key));
    }
}

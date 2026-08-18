package org.techhouse.simplejs.values;

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

/**
 * A typed-array view over a {@link JsArrayBuffer}. Elements are read/written through the buffer's
 * shared bytes in little-endian order (the platform endianness JS exposes for typed arrays), coerced
 * to and from the view's {@link Kind}. BigInt kinds surface {@link JsBigInt} elements; all others
 * surface {@link JsNumber}.
 */
public final class JsTypedArray extends JsValue {
    // An integer-indexed element is writable, enumerable and configurable: the spec made these
    // configurable so a shrinking resizable buffer can drop them.
    private static final JsObject.PropertyFlags INDEX_FLAGS = new JsObject.PropertyFlags(true, true, true);

    private PropertyTable table;
    private InterpreterOps ops;

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

    // The realm's ops, so an element write reached through a seam that carries none (a member
    // assignment, [[DefineOwnProperty]]) still runs the value's user-defined valueOf.
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

    /** The observable {@code byteOffset}, which the spec reports as 0 once the view is out of bounds. */
    public int byteOffset() {
        return isOutOfBounds() ? 0 : byteOffset;
    }

    /** The view's own offset regardless of bounds, for deriving a new view's geometry from this one. */
    public int rawByteOffset() {
        return byteOffset;
    }

    public boolean isLengthTracking() {
        return lengthTracking;
    }

    /**
     * IsTypedArrayOutOfBounds: a view whose window no longer fits inside its buffer. A detached buffer
     * makes every view out of bounds; a shrunk resizable buffer makes only the views that no longer
     * fit out of bounds (a length-tracking view just gets shorter, unless its very offset is gone).
     */
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

    // [[Set]] coerces the value before validating the index, but only when the receiver is this very
    // typed array; the receiver-less member seam cannot tell the two apart, so coercion is skipped
    // for an index merely out of range on a healthy view - the shape a foreign receiver produces.
    private InterpreterOps coercingOps(int index) {
        return isValidIndex(index) || isOutOfBounds() ? ops : null;
    }

    // IntegerIndexedElementSet coerces the value *before* checking the index, so a valueOf that
    // detaches or shrinks the buffer still runs and only then is the write dropped. Without an ops
    // seam there is no user code to run, so an already-invalid index skips the coercion rather than
    // raising a TypeError the spec would never reach.
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

    // Narrowing a double to a half through an intermediate float rounds twice, which turns a value a
    // hair above the halfway point into the wrong half. Round-to-odd on the first step makes the
    // second one land where a single correctly-rounded conversion would.
    private static float toOddFloat(double value) {
        final var narrowed = (float) value;
        if (narrowed == value || Double.isNaN(value) || Float.isInfinite(narrowed)
                || (Float.floatToRawIntBits(narrowed) & 1) != 0) {
            return narrowed;
        }
        return Math.nextAfter(narrowed, value);
    }

    // ToNumber on an object runs OrdinaryToPrimitive, but JsCoercion only recognises a plain
    // JsObject as one; an element written from another exotic (a typed array, an array, a function)
    // needs the same treatment.
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

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
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

    /**
     * CanonicalNumericIndexString: the number a property key denotes when it is exactly what
     * {@code Number::toString} would produce for it, else null. {@code "-0"} is canonical by fiat;
     * {@code "1.0"}, {@code "+1"} and {@code " 1"} are not, and stay ordinary property keys.
     */
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

    // IsValidIntegerIndex: a canonical numeric key only addresses an element when it is a
    // non-negative integer inside the live window; -0 never is.
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

    /**
     * The integer-indexed arm of {@code [[Set]]}. Writing through the typed array itself coerces the
     * value and then writes it (dropping an index the buffer no longer covers); a foreign receiver
     * with an index this view does not hold is answered {@code true} without coercing the value and
     * without ever reaching a setter the receiver inherits. Answers false only when the key is
     * ordinary and {@code OrdinarySet} should take over, so the receiver-aware member choke point has
     * to try this before it does anything else.
     */
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

    // The slot the coerced value will be written to, resolved *before* coercion but validated after
    // it: a valueOf that resizes the buffer can turn an out-of-bounds index into a live one. A
    // fractional or negative index (-0 included) can never name a slot, so it is dropped outright.
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

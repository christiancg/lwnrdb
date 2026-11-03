package org.techhouse.ejson.custom_types;

import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;
import org.techhouse.utils.VectorUtils;

public class JsonVector extends JsonCustom<double[]> {
    public static final String CUSTOM_TYPE_NAME = "vector";
    public static final String OPERATOR_NEAREST = "nearest";
    public static final int SIMHASH_BITS = 16;

    public static final int INDEX_KEY_LENGTH = 20;

    private static final int NO_SIGNATURE = -1;
    private static final int SIGNATURE_DIGITS = 4;
    private static final long HASH_SEED = 0x9E3779B97F4A7C15L;
    private static final long HASH_MULTIPLIER = 0xBF58476D1CE4E5B9L;
    private static final long HASH_FINAL_MULTIPLIER = 0x94D049BB133111EBL;
    private static final HexFormat HEX = HexFormat.of();

    private int signature = NO_SIGNATURE;

    public JsonVector(double[] customValue) {
        super(buildWireValue(customValue));
    }

    public JsonVector(String strValue) {
        super(strValue);
    }

    public JsonVector() {
        super();
    }

    private JsonVector(String indexKey, int signature) {
        super();
        this.value = indexKey;
        this.signature = signature;
    }

    public static JsonVector ofIndexKey(String indexKey) {
        if (indexKey.length() != INDEX_KEY_LENGTH) {
            throw new WrongFormatCustomTypeException(JsonVector.class.getName());
        }
        try {
            HexFormat.fromHexDigitsToLong(indexKey, SIGNATURE_DIGITS, INDEX_KEY_LENGTH);
            return new JsonVector(indexKey, HexFormat.fromHexDigits(indexKey, 0, SIGNATURE_DIGITS));
        } catch (IllegalArgumentException e) {
            throw new WrongFormatCustomTypeException(JsonVector.class.getName(), e);
        }
    }

    public JsonVector asIndexKey() {
        return isIndexKey() ? this : new JsonVector(indexKey(), signature());
    }

    public boolean isIndexKey() {
        return customValue == null && value != null;
    }

    public String indexKey() {
        if (isIndexKey()) {
            return value;
        }
        return indexKeyOf(signature(), customValue);
    }

    private static String indexKeyOf(int signature, double[] components) {
        return HEX.toHexDigits((short) signature) + HEX.toHexDigits(contentHashOf(components));
    }

    public long contentHash() {
        return isIndexKey()
                ? HexFormat.fromHexDigitsToLong(value, SIGNATURE_DIGITS, INDEX_KEY_LENGTH)
                : contentHashOf(customValue);
    }

    public static long contentHashOf(double[] components) {
        var hash = HASH_SEED ^ components.length;
        for (final var component : components) {
            hash = mix((hash ^ Double.doubleToLongBits(component)) * HASH_MULTIPLIER);
        }
        return mix(hash);
    }

    private static long mix(long hash) {
        var mixed = (hash ^ (hash >>> 30)) * HASH_MULTIPLIER;
        mixed = (mixed ^ (mixed >>> 27)) * HASH_FINAL_MULTIPLIER;
        return mixed ^ (mixed >>> 31);
    }

    @Override
    public JsonBaseElement deepCopy() {
        return isIndexKey() ? this : super.deepCopy();
    }

    private static String buildWireValue(double[] components) {
        final var sb = new StringBuilder("#" + CUSTOM_TYPE_NAME + "(");
        for (var i = 0; i < components.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(components[i]);
        }
        return sb.append(')').toString();
    }

    @Override
    public String canonicalSpelling() {
        return isIndexKey() ? value : buildWireValue(customValue);
    }

    @Override
    public String getCustomTypeName() {
        return CUSTOM_TYPE_NAME;
    }

    @Override
    protected double[] parse() {
        try {
            final var parts = stringDataValue().split(",");
            if (parts.length == 0 || (parts.length == 1 && parts[0].isBlank())) {
                throw new WrongFormatCustomTypeException(getClass().getName());
            }
            final var components = new double[parts.length];
            for (var i = 0; i < parts.length; i++) {
                components[i] = Double.parseDouble(parts[i].trim());
            }
            return components;
        } catch (WrongFormatCustomTypeException e) {
            throw e;
        } catch (Exception e) {
            throw new WrongFormatCustomTypeException(getClass().getName(), e);
        }
    }

    @Override
    public Integer compare(double[] another) {
        final var anotherSignature = VectorUtils.simHashBits(another, SIMHASH_BITS);
        final var bySignature = Integer.compareUnsigned(signature(), anotherSignature);
        if (bySignature != 0) {
            return bySignature;
        }
        return isIndexKey() ? value.compareTo(indexKeyOf(anotherSignature, another)) : compareByComponents(another);
    }

    @Override
    public Integer compareToCustom(JsonCustom<double[]> another) {
        if (another instanceof JsonVector otherVector) {
            final var bySignature = Integer.compareUnsigned(signature(), otherVector.signature());
            if (bySignature != 0) {
                return bySignature;
            }
            return isIndexKey() || otherVector.isIndexKey()
                    ? indexKey().compareTo(otherVector.indexKey())
                    : compareByComponents(otherVector.customValue);
        }
        return compare(another.getCustomValue());
    }

    private int compareByComponents(double[] another) {
        final var minLength = Math.min(customValue.length, another.length);
        for (var i = 0; i < minLength; i++) {
            final var byComponent = Double.compare(customValue[i], another[i]);
            if (byComponent != 0) {
                return byComponent;
            }
        }
        return Integer.compare(customValue.length, another.length);
    }

    public double[] vector() {
        return customValue;
    }

    public int signature() {
        var cached = signature;
        if (cached == NO_SIGNATURE) {
            cached = VectorUtils.simHashBits(customValue, SIMHASH_BITS);
            signature = cached;
        }
        return cached;
    }

    public String simHash() {
        return VectorUtils.simHash(customValue, SIMHASH_BITS);
    }

    @Override
    public Set<String> customRankingOperatorNames() {
        return Set.of(OPERATOR_NEAREST);
    }

    @Override
    public double applyCustomRankingOperator(String operatorName, Map<String, JsonBaseElement> args) {
        if (OPERATOR_NEAREST.equals(operatorName)) {
            return VectorUtils.cosineSimilarity(customValue, toVector(args.get("value")));
        }
        throw new UnsupportedOperationException(
                getCustomTypeName() + " does not support ranking operator " + operatorName);
    }

    public static double[] toVector(JsonBaseElement element) {
        if (element instanceof JsonVector vector) {
            return vector.vector();
        }
        if (element != null && element.isJsonString()) {
            return new JsonVector(element.asJsonString().getValue()).vector();
        }
        throw new WrongFormatCustomTypeException(JsonVector.class.getName());
    }
}

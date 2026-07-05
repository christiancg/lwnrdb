package org.techhouse.ejson.custom_types;

import java.util.Map;
import java.util.Set;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;
import org.techhouse.utils.VectorUtils;

// A dense vector stored as "#vector(v0,v1,...,vn)". It exposes the "nearest" ranking operator (cosine
// top-K, for semantic search) and orders its index by a SimHash signature so similar vectors cluster,
// enabling the approximate candidate pre-filter in VectorSimilarityIndexHelper.
public class JsonVector extends JsonCustom<double[]> {
    public static final String CUSTOM_TYPE_NAME = "vector";
    public static final String OPERATOR_NEAREST = "nearest";
    public static final int SIMHASH_BITS = 16;

    public JsonVector(double[] customValue) {
        super(buildWireValue(customValue));
    }

    public JsonVector(String strValue) {
        super(strValue);
    }

    public JsonVector() {
        super();
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

    // SimHash signature first (clusters the index), then component/length tie-break so 0 iff equal.
    @Override
    public Integer compare(double[] another) {
        final var bySignature = simHash().compareTo(VectorUtils.simHash(another, SIMHASH_BITS));
        if (bySignature != 0) {
            return bySignature;
        }
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

    public String simHash() {
        return VectorUtils.simHash(customValue, SIMHASH_BITS);
    }

    @Override
    public Set<String> customOperatorNames() {
        return Set.of();
    }

    @Override
    public boolean applyCustomOperator(String operatorName, Map<String, JsonBaseElement> args) {
        throw new UnsupportedOperationException(getCustomTypeName() + " has no predicate custom operators");
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

    // Accepts a vector already parsed as a JsonVector or as a raw "#vector(...)" string.
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

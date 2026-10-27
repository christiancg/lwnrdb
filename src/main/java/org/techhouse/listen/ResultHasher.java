package org.techhouse.listen;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.JsonElementWriter;
import org.techhouse.ejson.internal.JsonSizeEstimator;
import org.techhouse.ejson.internal.Utf8JsonOut;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;

public final class ResultHasher {
    private static final int MIN_PRESIZE = 16;
    private static final int MAX_PRESIZE = 1 << 26;
    private static final Set<AggregationStepType> ORDER_PRESERVING = EnumSet.of(AggregationStepType.FILTER,
            AggregationStepType.MAP, AggregationStepType.JOIN, AggregationStepType.LIMIT, AggregationStepType.SKIP);

    private ResultHasher() {
    }

    public static String hash(List<JsonObject> results) {
        return hash(results, true);
    }

    public static String hash(List<JsonObject> results, boolean ordered) {
        try (var encoded = encode(results, ordered)) {
            return encoded.hash();
        }
    }

    public static EncodedResults encode(List<JsonObject> results, boolean ordered) {
        final var out = Utf8JsonOut.acquire(Math.clamp(JsonSizeEstimator.estimate(results), MIN_PRESIZE, MAX_PRESIZE));
        try {
            final var digest = sha256();
            final var perDocument = ordered ? null : new byte[results.size()][];
            out.append('[');
            for (var i = 0; i < results.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                final var start = out.size();
                JsonElementWriter.write(results.get(i), out);
                if (perDocument != null) {
                    out.digestInto(digest, start, out.size());
                    perDocument[i] = digest.digest();
                }
            }
            out.append(']');
            if (perDocument == null) {
                out.digestInto(digest, 0, out.size());
            } else {
                Arrays.sort(perDocument, Arrays::compareUnsigned);
                for (final var documentDigest : perDocument) {
                    digest.update(documentDigest);
                }
            }
            return new EncodedResults(out, digest.digest());
        } catch (RuntimeException | Error e) {
            out.release();
            throw e;
        }
    }

    public static boolean ordersResults(List<BaseAggregationStep> steps) {
        if (steps == null) {
            return false;
        }
        for (var i = steps.size() - 1; i >= 0; i--) {
            final var step = steps.get(i);
            final var type = step.getType();
            if (type == AggregationStepType.SORT || isRankingFilter(step)) {
                return true;
            }
            if (!isOrderPreserving(steps, i)) {
                return false;
            }
        }
        return false;
    }

    private static boolean isOrderPreserving(List<BaseAggregationStep> steps, int index) {
        final var type = steps.get(index).getType();
        return ORDER_PRESERVING.contains(type) || (type == AggregationStepType.DISTINCT && index > 0);
    }

    public static int firstUnorderedCut(List<BaseAggregationStep> steps) {
        if (steps == null) {
            return -1;
        }
        for (var i = 0; i < steps.size(); i++) {
            final var step = steps.get(i);
            final var type = step.getType();
            if (type == AggregationStepType.SORT || isRankingFilter(step) || !ORDER_PRESERVING.contains(type)) {
                return -1;
            }
            if (type == AggregationStepType.LIMIT || type == AggregationStepType.SKIP) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isRankingFilter(BaseAggregationStep step) {
        if (!(step instanceof FilterAggregationStep filterStep)) {
            return false;
        }
        return isRankingOperator(filterStep.getOperator());
    }

    private static boolean isRankingOperator(BaseOperator operator) {
        if (operator.getType() == OperatorType.CUSTOM) {
            return CustomTypeFactory.isRankingOperator(((CustomOperator) operator).getCustomOperatorName());
        }
        if (operator.getType() == OperatorType.CONJUNCTION) {
            final var conjunction = (ConjunctionOperator) operator;
            final var type = conjunction.getConjunctionType();
            if (type != ConjunctionOperatorType.OR) {
                return false;
            }
            return conjunction.getOperators().stream().anyMatch(ResultHasher::isRankingOperator);
        }
        return false;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

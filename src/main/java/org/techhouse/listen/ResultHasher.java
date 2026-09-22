package org.techhouse.listen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public final class ResultHasher {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final Set<AggregationStepType> ORDER_PRESERVING = EnumSet.of(AggregationStepType.FILTER,
            AggregationStepType.MAP, AggregationStepType.JOIN, AggregationStepType.LIMIT, AggregationStepType.SKIP);

    private ResultHasher() {
    }

    public static String hash(List<JsonObject> results) {
        return hash(results, true);
    }

    public static String hash(List<JsonObject> results, boolean ordered) {
        if (ordered) {
            return digestOf(eJson.toJson(new ResultWrapper(results)));
        }
        final var perDocument = new ArrayList<String>(results.size());
        for (final var result : results) {
            perDocument.add(digestOf(eJson.toJson(result)));
        }
        Collections.sort(perDocument);
        return digestOf(String.join("", perDocument));
    }

    public static boolean ordersResults(List<BaseAggregationStep> steps) {
        if (steps == null) {
            return false;
        }
        for (var i = steps.size() - 1; i >= 0; i--) {
            final var type = steps.get(i).getType();
            if (type == AggregationStepType.SORT) {
                return true;
            }
            if (!ORDER_PRESERVING.contains(type)) {
                return false;
            }
        }
        return false;
    }

    private static String digestOf(String text) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static final class ResultWrapper {
        public final List<JsonObject> results;

        private ResultWrapper(List<JsonObject> results) {
            this.results = results;
        }
    }
}

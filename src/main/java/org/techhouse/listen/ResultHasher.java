package org.techhouse.listen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public final class ResultHasher {
    private static final EJson eJson = IocContainer.get(EJson.class);

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
        return steps != null && !steps.isEmpty() && steps.getLast().getType() == AggregationStepType.SORT;
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

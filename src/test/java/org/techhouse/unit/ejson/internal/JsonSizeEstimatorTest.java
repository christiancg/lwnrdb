package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.JsonSizeEstimator;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.ListenResponse;
import org.techhouse.ops.resp.OperationResponse;

public class JsonSizeEstimatorTest {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final long SEED = 0xE57_1A7EL;
    private static final String TEXT_POOL = "abcdefghijklmnopqrstuvwxyz ABCXYZ0123456789_-界面ünï😀";

    private static String randomText(Random random, int maxLength) {
        final var length = random.nextInt(maxLength + 1);
        final var out = new StringBuilder(length);
        for (var i = 0; i < length; i++) {
            out.append(TEXT_POOL.charAt(random.nextInt(TEXT_POOL.length() - 2)));
        }
        return out.toString();
    }

    private static JsonBaseElement bool(boolean value) {
        final var holder = new JsonObject();
        holder.addProperty("value", value);
        return holder.get("value");
    }

    private static JsonBaseElement randomValue(Random random, int depth) {
        final var kind = depth >= 3 ? random.nextInt(5) : random.nextInt(7);
        return switch (kind) {
            case 0 -> new JsonString(randomText(random, 40));
            case 1 -> new JsonNumber(random.nextInt(2_000_001) - 1_000_000);
            case 2 -> new JsonNumber(random.nextDouble() * 1000);
            case 3 -> bool(random.nextBoolean());
            case 4 -> JsonNull.INSTANCE;
            case 5 -> randomObject(random, depth + 1);
            default -> {
                final var array = new JsonArray();
                final var size = random.nextInt(6);
                for (var i = 0; i < size; i++) {
                    array.add(randomValue(random, depth + 1));
                }
                yield array;
            }
        };
    }

    private static JsonObject randomObject(Random random, int depth) {
        final var object = new JsonObject();
        final var fields = 1 + random.nextInt(8);
        for (var i = 0; i < fields; i++) {
            object.add("k" + randomText(random, 11), randomValue(random, depth));
        }
        return object;
    }

    public static List<JsonObject> randomDocuments(long seed, int count) {
        final var random = new Random(seed);
        final var documents = new ArrayList<JsonObject>(count);
        for (var i = 0; i < count; i++) {
            final var document = randomObject(random, 0);
            document.addProperty("_id", "doc-" + i);
            documents.add(document);
        }
        return documents;
    }

    private static void assertWithinTwice(long estimate, int real, String what) {
        assertTrue(estimate * 2 >= real && estimate <= real * 2L,
                what + ": estimated " + estimate + " chars for " + real);
    }

    @Test
    public void estimateIsWithinTwiceTheRealLength() {
        for (final var document : randomDocuments(SEED, 2000)) {
            assertWithinTwice(JsonSizeEstimator.estimate(document), eJson.toJson(document).length(),
                    eJson.toJson(document));
        }
    }

    @Test
    public void test_result_bearing_responses_estimate_within_twice_their_real_length() {
        final var rows = randomDocuments(SEED + 1, 300);
        final List<OperationResponse> responses = List.of(new AggregateResponse("ok", rows),
                new ListenResponse("listen-1", rows, "hash", true), new FindByIdResponse("ok", rows.getFirst()));
        for (final var response : responses) {
            assertWithinTwice(response.estimatedJsonChars(), eJson.toJson(response).length(),
                    response.getClass().getSimpleName());
        }
    }

    @Test
    public void test_scalars_and_empty_containers() {
        assertEquals(4, JsonSizeEstimator.estimate((JsonBaseElement) null));
        assertEquals(4, JsonSizeEstimator.estimate(JsonNull.INSTANCE));
        assertEquals(4, JsonSizeEstimator.estimate(bool(true)));
        assertEquals(5, JsonSizeEstimator.estimate(bool(false)));
        assertEquals(5, JsonSizeEstimator.estimate(new JsonString("abc")));
        assertEquals(4, JsonSizeEstimator.estimate(new JsonNumber(-123)));
        assertEquals(1, JsonSizeEstimator.estimate(new JsonNumber(0)));
        assertEquals(18, JsonSizeEstimator.estimate(new JsonNumber("1.5")));
        assertEquals(18, JsonSizeEstimator.estimate(new JsonNumber(0.1 + 0.2)));
        assertEquals(2, JsonSizeEstimator.estimate(new JsonObject()));
        assertEquals(2, JsonSizeEstimator.estimate(new JsonArray()));
        assertEquals(2, JsonSizeEstimator.estimate(List.of()));
        assertEquals(4, JsonSizeEstimator.estimate((List<JsonObject>) null));
    }

    @Test
    public void test_a_response_without_results_estimates_the_base_size() {
        assertEquals(256, OperationResponse.ok(OperationType.SAVE, "x").estimatedJsonChars());
        assertEquals(256 + 4, new FindByIdResponse("ok", null).estimatedJsonChars());
    }
}

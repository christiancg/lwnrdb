package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.GetDatabaseStatsResponse;
import org.techhouse.ops.resp.GetSchemaResponse;
import org.techhouse.ops.resp.ListCollectionsResponse;
import org.techhouse.ops.resp.ListDatabasesResponse;
import org.techhouse.ops.resp.ListProceduresResponse;
import org.techhouse.ops.resp.ListSchedulesResponse;
import org.techhouse.ops.resp.ListScriptsResponse;
import org.techhouse.ops.resp.ListTransactionsResponse;
import org.techhouse.ops.resp.ListTriggerRunsResponse;
import org.techhouse.ops.resp.ListTriggersResponse;
import org.techhouse.ops.resp.ListUsersResponse;
import org.techhouse.ops.resp.OperationResponse;

public class ListingResponseEstimateTest {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final int PAYLOAD_CHARS = 100_000;
    private static final String LARGE = "s".repeat(PAYLOAD_CHARS);

    private static JsonObject large() {
        final var object = new JsonObject();
        object.addProperty("source", LARGE);
        return object;
    }

    private static void assertCoversItsJson(OperationResponse response) {
        final var encoded = eJson.toJson(response).length();
        final var estimate = response.estimatedJsonChars();
        assertTrue(encoded > PAYLOAD_CHARS, response.getClass().getSimpleName());
        assertTrue(estimate >= PAYLOAD_CHARS && estimate >= encoded / 2,
                () -> response.getClass().getSimpleName() + " estimates " + estimate + " for " + encoded);
    }

    @Test
    public void everyRefusableListingEstimatesItsPayload() {
        final List<Function<List<JsonObject>, OperationResponse>> objectLists = List.of(
                rows -> new ListProceduresResponse("ok", rows), rows -> new ListSchedulesResponse("ok", rows),
                rows -> new ListScriptsResponse("ok", rows), rows -> new ListTransactionsResponse("ok", rows),
                rows -> new ListTriggerRunsResponse("ok", rows), rows -> new ListTriggersResponse("ok", rows),
                rows -> new ListUsersResponse("ok", rows));
        for (final var listing : objectLists) {
            assertCoversItsJson(listing.apply(List.of(large())));
        }
        assertCoversItsJson(new GetDatabaseStatsResponse("ok", large()));
        assertCoversItsJson(new GetSchemaResponse("ok", large()));
        assertCoversItsJson(new ListCollectionsResponse("ok", List.of(LARGE, "b")));
        assertCoversItsJson(new ListDatabasesResponse("ok", List.of(LARGE)));
    }

    @Test
    public void anAbsentListingEstimatesOnlyItsEnvelope() {
        assertTrue(new ListDatabasesResponse("ok", null).estimatedJsonChars() < 300);
        assertTrue(new ListProceduresResponse("ok", null).estimatedJsonChars() < 300);
    }
}

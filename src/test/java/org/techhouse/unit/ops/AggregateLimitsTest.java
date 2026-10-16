package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AggregateDeadline;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregateLimitException;
import org.techhouse.ops.EntrySizeGuard;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregateLimitsTest {
    private static final int SAVED_DOCUMENTS = 300;
    private static final int REJECTED_DOCUMENTS = 4_000;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Configuration configuration = Configuration.getInstance();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var objects = new StringBuilder(SAVED_DOCUMENTS * 24);
        for (var i = 0; i < SAVED_DOCUMENTS; i++) {
            objects.append(i == 0 ? "" : ",").append("{\"_id\":\"d").append(i).append("\",\"n\":").append(i)
                    .append('}');
        }
        assertEquals(OperationStatus.OK, run("{\"type\":\"BULK_SAVE\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"objects\":[" + objects + "]}").getStatus());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxFilterOperands", ConfigKey.MAX_FILTER_OPERANDS.defaultValue());
        TestUtils.setPrivateField(configuration, "aggregateTimeoutMs", ConfigKey.AGGREGATE_TIMEOUT_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse run(String json) {
        return processor.processMessage(RequestParser.parseRequest(json));
    }

    private static String aggregate(String steps) {
        return "{\"type\":\"AGGREGATE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"aggregationSteps\":[" + steps + "]}";
    }

    private static String validationCode(String json) {
        final var result = RequestValidator.validate(RequestParser.parseRequest(json));
        return result.isValid() ? null : result.getErrorCode().getCode();
    }

    private static String doublingConcat(int levels) {
        final var operators = new StringBuilder(160 * (levels + 1)).append(
                "{\"fieldName\":\"g0\",\"operator\":{\"type\":\"CONCAT\",\"operands\":[\"-AAAAAAAA\",\"-AAAAAAAA\"]}}");
        for (var i = 1; i <= levels; i++) {
            operators.append(",{\"fieldName\":\"g").append(i).append("\",\"operator\":{\"type\":\"CONCAT\",")
                    .append("\"operands\":[\"g").append(i - 1).append("\",\"g").append(i - 1).append("\"]}}");
        }
        return "{\"type\":\"FILTER\",\"operator\":{\"fieldOperatorType\":\"EQUALS\",\"field\":\"_id\",\"value\":\"d1\"}},"
                + "{\"type\":\"MAP\",\"operators\":[" + operators + "]}";
    }

    @Test
    public void test_a_doubling_concat_stops_at_the_maximum_entry_size() {
        final var response = run(aggregate(doublingConcat(30)));
        assertEquals(ErrorCode.MAP_RESULT_TOO_LARGE.getCode(), response.getErrorCode());
    }

    @Test
    public void test_an_index_only_count_after_a_failing_concat_answers_like_the_scan() {
        final var map = doublingConcat(30).substring(doublingConcat(30).indexOf("{\"type\":\"MAP\""));
        final var counted = run(aggregate(map + ",{\"type\":\"COUNT\"}"));
        final var scanned = run(aggregate("{\"type\":\"SKIP\",\"skip\":0}," + map + ",{\"type\":\"COUNT\"}"));
        assertEquals(ErrorCode.MAP_RESULT_TOO_LARGE.getCode(), scanned.getErrorCode());
        assertEquals(scanned.getErrorCode(), counted.getErrorCode());
    }

    @Test
    public void test_the_concat_cap_counts_utf8_bytes_not_characters() throws Exception {
        TestUtils.setPrivateField(configuration, "maxEntrySize", "10");
        try {
            assertFalse(EntrySizeGuard.exceedsMaxEntrySize("abcdefghij"));
            assertTrue(EntrySizeGuard.exceedsMaxEntrySize("é".repeat(6)));
            assertFalse(EntrySizeGuard.exceedsMaxEntrySize("é".repeat(5)));
            assertTrue(EntrySizeGuard.exceedsMaxEntrySize("\uD83D\uDE00".repeat(3)));
            assertFalse(EntrySizeGuard.exceedsMaxEntrySize("\uD83D\uDE00\uD83D\uDE00"));
        } finally {
            TestUtils.setPrivateField(configuration, "maxEntrySize", ConfigKey.MAX_ENTRY_SIZE.defaultValue());
        }
    }

    @Test
    public void test_every_handler_answers_aggregate_refusals_with_their_own_codes() {
        final var limited = OperationResponse.respondOrError(OperationType.LISTEN, ErrorCode.ERROR_LISTEN, () -> {
            throw new AggregateLimitException(ErrorCode.MAP_RESULT_TOO_LARGE);
        });
        assertEquals(ErrorCode.MAP_RESULT_TOO_LARGE.getCode(), limited.getErrorCode());
        final var expired = OperationResponse.respondOrError(OperationType.LIST_USERS, ErrorCode.ERROR_LISTING_USERS,
                () -> {
                    try (var _ = AggregateDeadline.open(1L)) {
                        Thread.sleep(5);
                        AggregateDeadline.watch(IntStream.range(0, 10_000).boxed()).forEach(_ -> {
                        });
                    }
                    return null;
                });
        assertEquals(ErrorCode.AGGREGATE_TIMEOUT.getCode(), expired.getErrorCode());
    }

    @Test
    public void test_a_modest_concat_still_answers() {
        final var response = run(aggregate(doublingConcat(3)));
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
    }

    @Test
    public void test_membership_operands_past_the_limit_are_refused() throws Exception {
        TestUtils.setPrivateField(configuration, "maxFilterOperands", 3);
        assertEquals(ErrorCode.FILTER_OPERAND_LIMIT.getCode(), validationCode(aggregate(
                "{\"type\":\"FILTER\",\"operator\":{\"fieldOperatorType\":\"IN\",\"field\":\"n\",\"value\":[1,2,3,4]}}")));
        assertNull(validationCode(aggregate(
                "{\"type\":\"FILTER\",\"operator\":{\"fieldOperatorType\":\"NOT_IN\",\"field\":\"n\",\"value\":[1,2,3]}}")));
        TestUtils.setPrivateField(configuration, "maxFilterOperands", 0);
        assertNull(validationCode(aggregate(
                "{\"type\":\"FILTER\",\"operator\":{\"fieldOperatorType\":\"IN\",\"field\":\"n\",\"value\":[1,2,3,4]}}")));
    }

    @Test
    public void test_polygon_vertices_past_the_limit_are_refused() throws Exception {
        TestUtils.setPrivateField(configuration, "maxFilterOperands", 3);
        final var polygon = "[\"#geo(0,0)\",\"#geo(0,1)\",\"#geo(1,1)\",\"#geo(1,0)\"]";
        final var within = aggregate("{\"type\":\"FILTER\",\"operator\":{\"field\":\"loc\","
                + "\"customOperatorName\":\"within\",\"polygon\":" + polygon + "}}");
        assertEquals(ErrorCode.FILTER_OPERAND_LIMIT.getCode(), validationCode(within));
        TestUtils.setPrivateField(configuration, "maxFilterOperands", 4);
        assertNull(validationCode(within));
    }

    @Test
    public void test_a_map_field_nested_past_the_request_limit_is_refused() {
        final var deep = "a.".repeat(128) + "z";
        assertEquals(ErrorCode.VALIDATION_ERROR.getCode(),
                validationCode(aggregate("{\"type\":\"MAP\",\"operators\":[{\"fieldName\":\"" + deep
                        + "\",\"operator\":{\"type\":\"CONCAT\"," + "\"operands\":[\"-x\"]}}]}")));
        assertNull(validationCode(aggregate("{\"type\":\"MAP\",\"operators\":[{\"fieldName\":\"" + "a.".repeat(100)
                + "z\",\"operator\":{\"type\":\"CONCAT\",\"operands\":[\"-x\"]}}]}")));
    }

    @Test
    public void test_a_pipeline_past_its_time_budget_answers_408_6() throws Exception {
        TestUtils.setPrivateField(configuration, "aggregateTimeoutMs", 1L);
        final var response = run(aggregate("{\"type\":\"FILTER\",\"operator\":{\"script\":"
                + "\"export default (doc) => { let s = 0; for (let i = 0; i < 2000; i++) { s += i; } return s > 0; };\"}}"));
        assertEquals(ErrorCode.AGGREGATE_TIMEOUT.getCode(), response.getErrorCode(), response.getMessage());
    }

    @Test
    public void test_a_pipeline_within_its_time_budget_answers() {
        final var response = run(aggregate("{\"type\":\"COUNT\"}"));
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertFalse(response.getMessage().isEmpty());
        assertNull(response.getErrorCode());
    }

    @Test
    public void rejectedRowsAdvanceTheDeadline() throws Exception {
        final var objects = new StringBuilder(REJECTED_DOCUMENTS * 1_100);
        for (var i = 0; i < REJECTED_DOCUMENTS; i++) {
            objects.append(i == 0 ? "" : ",").append("{\"_id\":\"r").append(i).append("\",\"s\":\"").repeat("p", 1_000)
                    .append("\"}");
        }
        assertEquals(OperationStatus.OK, run("{\"type\":\"BULK_SAVE\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"objects\":[" + objects + "]}").getStatus());
        final var rejectEveryRow = aggregate(
                "{\"type\":\"FILTER\",\"operator\":{\"fieldOperatorType\":\"CONTAINS\",\"field\":\"s\",\"value\":\"zz\"}}");
        assertEquals(ErrorCode.NO_RESULTS.getCode(), run(rejectEveryRow).getErrorCode());
        TestUtils.setPrivateField(configuration, "aggregateTimeoutMs", 1L);
        final var response = run(rejectEveryRow);
        assertEquals(ErrorCode.AGGREGATE_TIMEOUT.getCode(), response.getErrorCode(), response.getMessage());
    }
}

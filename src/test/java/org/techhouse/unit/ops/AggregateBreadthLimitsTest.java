package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AggregateDeadline;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.validations.AggregationStepValidator;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregateBreadthLimitsTest {
    private static final String JOINED = "joinedColl";
    private static final int JOINED_DOCUMENTS = 30;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Configuration configuration = Configuration.getInstance();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        assertEquals(OperationStatus.OK, run(write("SAVE", TestGlobals.COLL,
                "\"object\":{\"_id\":\"left\",\"n\":1," + "\"s\":\"" + "x".repeat(300) + "\"}")).getStatus());
        assertEquals(OperationStatus.OK, run("{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + JOINED + "\"}").getStatus());
        final var objects = new StringBuilder(JOINED_DOCUMENTS * 140);
        for (var i = 0; i < JOINED_DOCUMENTS; i++) {
            objects.append(i == 0 ? "" : ",").append("{\"_id\":\"j").append(i).append("\",\"n\":1,\"pad\":\"")
                    .repeat("p", 100).append("\"}");
        }
        assertEquals(OperationStatus.OK, run(write("BULK_SAVE", JOINED, "\"objects\":[" + objects + "]")).getStatus());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxFilterOperands", ConfigKey.MAX_FILTER_OPERANDS.defaultValue());
        TestUtils.setPrivateField(configuration, "maxEntrySize", ConfigKey.MAX_ENTRY_SIZE.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse run(String json) {
        return processor.processMessage(RequestParser.parseRequest(json));
    }

    private static String write(String type, String collection, String payload) {
        return "{\"type\":\"" + type + "\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + collection + "\"," + payload + "}";
    }

    private static String aggregate(String steps) {
        return "{\"type\":\"AGGREGATE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"aggregationSteps\":[" + steps + "]}";
    }

    private static String validationCode(String json) {
        final var result = RequestValidator.validate(RequestParser.parseRequest(json));
        return result.isValid() ? null : result.getErrorCode().getCode();
    }

    private static String equalsOperator(int value) {
        return "{\"fieldOperatorType\":\"EQUALS\",\"field\":\"n\",\"value\":" + value + "}";
    }

    private static String conjunction(String type, List<String> operators) {
        return "{\"conjunctionType\":\"" + type + "\",\"operators\":[" + String.join(",", operators) + "]}";
    }

    private static List<String> equalsOperators(int count) {
        return IntStream.range(0, count).mapToObj(AggregateBreadthLimitsTest::equalsOperator).toList();
    }

    private static String filter(String operator) {
        return "{\"type\":\"FILTER\",\"operator\":" + operator + "}";
    }

    private static String join() {
        return "{\"type\":\"JOIN\",\"joinCollection\":\"" + JOINED
                + "\",\"localField\":\"n\",\"remoteField\":\"n\",\"asField\":\"m\"}";
    }

    @Test
    public void test_a_wide_conjunction_is_refused() throws Exception {
        TestUtils.setPrivateField(configuration, "maxFilterOperands", 4);
        assertEquals(ErrorCode.FILTER_OPERAND_LIMIT.getCode(),
                validationCode(aggregate(filter(conjunction("AND", equalsOperators(5))))));
        assertNull(validationCode(aggregate(filter(conjunction("AND", equalsOperators(3))))));
    }

    @Test
    public void test_a_filter_tree_past_the_total_node_cap_is_refused() throws Exception {
        TestUtils.setPrivateField(configuration, "maxFilterOperands", 8);
        final var narrowBranches = new ArrayList<String>();
        for (var i = 0; i < 3; i++) {
            narrowBranches.add(conjunction("OR", equalsOperators(3)));
        }
        assertEquals(ErrorCode.FILTER_OPERAND_LIMIT.getCode(),
                validationCode(aggregate(filter(conjunction("AND", narrowBranches)))));
        assertNull(validationCode(aggregate(filter(conjunction("AND", narrowBranches.subList(0, 1))))));
    }

    @Test
    public void test_a_map_past_the_operator_node_cap_is_refused() throws Exception {
        TestUtils.setPrivateField(configuration, "maxFilterOperands", 4);
        final var operands = "\"operands\":[\"-a\",\"-b\",\"-c\",\"-d\",\"-e\"]";
        assertEquals(ErrorCode.FILTER_OPERAND_LIMIT.getCode(),
                validationCode(aggregate(
                        "{\"type\":\"MAP\",\"operators\":[{\"fieldName\":\"c\",\"operator\":{\"type\":\"CONCAT\","
                                + operands + "}}]}")));
        assertNull(validationCode(aggregate("{\"type\":\"MAP\",\"operators\":[{\"fieldName\":\"c\","
                + "\"operator\":{\"type\":\"CONCAT\",\"operands\":[\"-a\",\"-b\"]}}]}")));
    }

    @Test
    public void test_the_deadline_counts_eager_conjunction_elements() throws Exception {
        final var rows = IntStream.range(0, 5_000).mapToObj(i -> {
            final var row = new JsonObject();
            row.add("n", new JsonNumber(-i));
            return row;
        }).toList();
        final var operator = ((AggregateRequest) RequestParser
                .parseRequest(aggregate(filter(conjunction("OR", equalsOperators(2)))))).getAggregationSteps()
                .getFirst();
        final var conjunction = ((FilterAggregationStep) operator).getOperator();
        try (var _ = AggregateDeadline.open(1L)) {
            Thread.sleep(5);
            assertThrows(AggregateDeadline.Expired.class, () -> FilterOperatorHelper
                    .processOperator(conjunction, rows.stream(), TestGlobals.DB, TestGlobals.COLL).toList());
        }
    }

    private static String repeatedMap(String operatorType, int copies) {
        final var operators = new ArrayList<String>();
        for (var i = 0; i < copies; i++) {
            operators.add("{\"fieldName\":\"c" + i + "\",\"operator\":" + operatorType + "}");
        }
        return "{\"type\":\"MAP\",\"operators\":[" + String.join(",", operators) + "]}";
    }

    @Test
    public void test_a_map_that_grows_a_document_past_maxEntrySize_fails() throws Exception {
        TestUtils.setPrivateField(configuration, "maxEntrySize", "1Kb");
        final var concat = "{\"type\":\"CONCAT\",\"operands\":[\"s\"]}";
        assertEquals(OperationStatus.OK, run(aggregate(repeatedMap(concat, 3))).getStatus());
        assertEquals(ErrorCode.MAP_RESULT_TOO_LARGE.getCode(), run(aggregate(repeatedMap(concat, 4))).getErrorCode());
    }

    @Test
    public void test_a_map_of_string_casts_counts_toward_the_document_total() throws Exception {
        TestUtils.setPrivateField(configuration, "maxEntrySize", "1Kb");
        final var cast = "{\"type\":\"CAST\",\"fieldName\":\"s\",\"toType\":\"STRING\"}";
        assertEquals(ErrorCode.MAP_RESULT_TOO_LARGE.getCode(), run(aggregate(repeatedMap(cast, 4))).getErrorCode());
    }

    @Test
    public void test_a_join_past_the_result_cap_is_refused() throws Exception {
        assertEquals(OperationStatus.OK, run(aggregate(join())).getStatus());
        TestUtils.setPrivateField(configuration, "maxEntrySize", "2Kb");
        final var scanned = run(aggregate(join()));
        assertEquals(OperationStatus.OK, run(write("CREATE_INDEX", JOINED, "\"fieldName\":\"n\"")).getStatus());
        final var indexed = run(aggregate(join()));
        assertEquals(ErrorCode.MAP_RESULT_TOO_LARGE.getCode(), scanned.getErrorCode());
        assertEquals(scanned.getErrorCode(), indexed.getErrorCode());
    }

    @Test
    public void test_a_count_after_a_failing_join_answers_like_the_scan() throws Exception {
        assertEquals(OperationStatus.OK,
                run(write("CREATE_INDEX", TestGlobals.COLL, "\"fieldName\":\"n\"")).getStatus());
        TestUtils.setPrivateField(configuration, "maxEntrySize", "2Kb");
        final var counted = run(aggregate(filter(equalsOperator(1)) + "," + join() + ",{\"type\":\"COUNT\"}"));
        assertEquals(ErrorCode.MAP_RESULT_TOO_LARGE.getCode(), counted.getErrorCode());
    }

    @Test
    public void test_joins_and_casting_maps_can_fail_while_running() {
        final var joinSteps = ((AggregateRequest) RequestParser.parseRequest(aggregate(join()))).getAggregationSteps();
        final var castSteps = ((AggregateRequest) RequestParser.parseRequest(
                aggregate(repeatedMap("{\"type\":\"CAST\",\"fieldName\":\"s\",\"toType\":\"NUMBER\"}", 1))))
                .getAggregationSteps();
        final var sumSteps = ((AggregateRequest) RequestParser
                .parseRequest(aggregate(repeatedMap("{\"type\":\"SUM\",\"operands\":[\"n\"]}", 1))))
                .getAggregationSteps();
        assertTrue(AggregationStepValidator.canFailWhileRunning(joinSteps));
        assertTrue(AggregationStepValidator.canFailWhileRunning(castSteps));
        assertFalse(AggregationStepValidator.canFailWhileRunning(sumSteps));
    }
}

package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FilterArrayObjectOperandTest {
    private static final String ARRAY_FIELD = "tags";
    private static final String OBJECT_FIELD = "meta";
    private Cache cache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void insert(String id, String field, JsonBaseElement value) throws IOException {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add(field, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private List<String> matchedIds(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        final var ids = new ArrayList<String>();
        for (final var document : AggregationOperationHelper.processAggregation(request)) {
            ids.add(document.get(Globals.PK_FIELD).asJsonString().getValue());
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    private JsonArray arrayOperand() {
        final var array = new JsonArray();
        array.add(new JsonString("a"));
        array.add(new JsonString("b"));
        return array;
    }

    private JsonObject objectOperand() {
        final var object = new JsonObject();
        object.add("k", new JsonString("v"));
        return object;
    }

    private List<String> arrayFilter(FieldOperatorType type) throws IOException {
        return matchedIds(new FilterAggregationStep(new FieldOperator(type, ARRAY_FIELD, arrayOperand())));
    }

    private List<String> objectFilter(FieldOperatorType type) throws IOException {
        return matchedIds(new FilterAggregationStep(new FieldOperator(type, OBJECT_FIELD, objectOperand())));
    }

    @Test
    public void test_not_equals_array_operand_against_scalar_field_matches() throws IOException {
        insert("s_string", ARRAY_FIELD, new JsonString("solo"));
        assertEquals(List.of("s_string"), arrayFilter(FieldOperatorType.NOT_EQUALS));
    }

    @Test
    public void test_not_equals_array_operand_against_null_field_matches() throws IOException {
        insert("s_null", ARRAY_FIELD, JsonNull.INSTANCE);
        assertEquals(List.of("s_null"), arrayFilter(FieldOperatorType.NOT_EQUALS));
    }

    @Test
    public void test_not_in_array_operand_against_null_field_matches() throws IOException {
        insert("s_null", ARRAY_FIELD, JsonNull.INSTANCE);
        assertEquals(List.of("s_null"), arrayFilter(FieldOperatorType.NOT_IN));
    }

    @Test
    public void test_not_equals_object_operand_against_scalar_field_matches() throws IOException {
        insert("m_number", OBJECT_FIELD, new JsonNumber(3));
        assertEquals(List.of("m_number"), objectFilter(FieldOperatorType.NOT_EQUALS));
    }

    @Test
    public void test_equals_array_operand_against_scalar_field_still_does_not_match() throws IOException {
        insert("s_string", ARRAY_FIELD, new JsonString("solo"));
        assertEquals(List.of(), arrayFilter(FieldOperatorType.EQUALS));
    }

    @Test
    public void test_equals_object_operand_against_scalar_field_still_does_not_match() throws IOException {
        insert("m_number", OBJECT_FIELD, new JsonNumber(3));
        assertEquals(List.of(), objectFilter(FieldOperatorType.EQUALS));
    }
}

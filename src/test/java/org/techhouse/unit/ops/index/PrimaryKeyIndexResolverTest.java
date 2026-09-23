package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.PrimaryKeyIndexResolver;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PrimaryKeyIndexResolverTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var cache = IocContainer.get(Cache.class);
        cache.putAdminCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL),
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "seed", 0, 100, 0));
        for (final var id : new String[]{"a", "b", "c"}) {
            final var obj = new JsonObject();
            obj.add(Globals.PK_FIELD, new JsonString(id));
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
            entry.set_id(id);
            TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        }
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private Set<String> resolve(FieldOperatorType type, JsonBaseElement operand) throws IOException {
        return PrimaryKeyIndexResolver.resolve(new FieldOperator(type, Globals.PK_FIELD, operand), TestGlobals.DB,
                TestGlobals.COLL);
    }

    private static JsonArray arrayOf(JsonBaseElement... values) {
        final var array = new JsonArray();
        for (final var value : values) {
            array.add(value);
        }
        return array;
    }

    @Test
    public void test_equals_resolves_the_single_id() throws IOException {
        assertEquals(Set.of("b"), resolve(FieldOperatorType.EQUALS, new JsonString("b")));
    }

    @Test
    public void test_equals_on_a_missing_id_resolves_empty() throws IOException {
        assertEquals(Set.of(), resolve(FieldOperatorType.EQUALS, new JsonString("missing")));
    }

    @Test
    public void test_not_equals_resolves_the_complement() throws IOException {
        assertEquals(Set.of("a", "c"), resolve(FieldOperatorType.NOT_EQUALS, new JsonString("b")));
    }

    @Test
    public void test_in_resolves_every_present_operand() throws IOException {
        assertEquals(Set.of("a", "c"),
                resolve(FieldOperatorType.IN, arrayOf(new JsonString("a"), new JsonString("c"))));
    }

    @Test
    public void test_not_in_resolves_the_complement() throws IOException {
        assertEquals(Set.of("b"), resolve(FieldOperatorType.NOT_IN, arrayOf(new JsonString("a"), new JsonString("c"))));
    }

    @Test
    public void test_contains_resolves_by_substring() throws IOException {
        assertEquals(Set.of("a"), resolve(FieldOperatorType.CONTAINS, new JsonString("a")));
    }

    @Test
    public void test_range_operators_decline_so_the_caller_scans() throws IOException {
        for (final var type : new FieldOperatorType[]{FieldOperatorType.GREATER_THAN,
                FieldOperatorType.GREATER_THAN_EQUALS, FieldOperatorType.SMALLER_THAN,
                FieldOperatorType.SMALLER_THAN_EQUALS}) {
            assertNull(resolve(type, new JsonString("b")), type + " must decline rather than resolve");
        }
    }

    @Test
    public void test_a_non_string_operand_resolves_empty() throws IOException {
        assertEquals(Set.of(), resolve(FieldOperatorType.EQUALS, new JsonNumber(1)));
    }

    @Test
    public void test_a_non_array_operand_for_in_resolves_empty() throws IOException {
        assertEquals(Set.of(), resolve(FieldOperatorType.IN, new JsonString("a")));
    }

    @Test
    public void test_resolution_is_case_sensitive() throws IOException {
        assertEquals(Set.of(), resolve(FieldOperatorType.EQUALS, new JsonString("A")));
    }
}

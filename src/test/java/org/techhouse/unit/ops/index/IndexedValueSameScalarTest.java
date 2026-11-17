package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.IndexedValue;

public class IndexedValueSameScalarTest {

    @Test
    public void test_numbers_compare_as_the_index_keys_them() {
        assertTrue(IndexedValue.holdsSameScalar(5.0, new JsonNumber(5)));
        assertTrue(IndexedValue.holdsSameScalar(1.0, new JsonNumber("1.0")));
        assertTrue(IndexedValue.holdsSameScalar(0.0, new JsonNumber(-0.0)));
        assertTrue(IndexedValue.holdsSameScalar(Double.NaN, new JsonNumber(Double.NaN)));
        assertFalse(IndexedValue.holdsSameScalar(5.0, new JsonNumber(5.5)));
    }

    @Test
    public void test_strings_must_match_their_exact_spelling() {
        assertTrue(IndexedValue.holdsSameScalar("Abc", new JsonString("Abc")));
        assertFalse(IndexedValue.holdsSameScalar("Abc", new JsonString("abc")));
    }

    @Test
    public void test_booleans_compare_by_value() {
        assertTrue(IndexedValue.holdsSameScalar(Boolean.TRUE, JsonBoolean.of(true)));
        assertFalse(IndexedValue.holdsSameScalar(Boolean.TRUE, JsonBoolean.of(false)));
    }

    @Test
    public void test_a_value_of_another_kind_never_matches() {
        assertFalse(IndexedValue.holdsSameScalar(5.0, new JsonString("5")));
        assertFalse(IndexedValue.holdsSameScalar("true", JsonBoolean.of(true)));
        assertFalse(IndexedValue.holdsSameScalar(Boolean.TRUE, new JsonNumber(1)));
    }

    @Test
    public void test_custom_types_containers_and_null_always_take_the_full_path() {
        IocContainer.get(EJson.class);
        final var geo = CustomTypeFactory.getCustomTypeInstance(new JsonString("#geo(1,2)"));
        assertFalse(IndexedValue.holdsSameScalar(geo.getValue(), geo));
        assertFalse(IndexedValue.holdsSameScalar("#geo(1,2)", geo));
        assertFalse(IndexedValue.holdsSameScalar("x", new JsonObject()));
        assertFalse(IndexedValue.holdsSameScalar("x", new JsonArray()));
        assertFalse(IndexedValue.holdsSameScalar("x", JsonNull.INSTANCE));
        assertFalse(IndexedValue.holdsSameScalar("x", null));
    }
}

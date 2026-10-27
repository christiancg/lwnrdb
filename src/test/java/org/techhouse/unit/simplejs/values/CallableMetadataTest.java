package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.values.CallableMetadata;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class CallableMetadataTest {
    @Test
    public void test_reads_and_deletes_allocate_no_table() {
        final var metadata = new CallableMetadata();
        assertNull(metadata.getProperty("name"));
        assertFalse(metadata.hasProperty("length"));
        assertFalse(metadata.isDeleted("name"));
        assertTrue(metadata.deleteProperty("x"));
        assertEquals(List.of(), metadata.propertyKeys());
        assertEquals(List.of(), metadata.enumerablePropertyKeys());
        assertFalse(metadata.hasTable());
    }

    @Test
    public void test_the_first_own_property_allocates_the_table() {
        final var metadata = new CallableMetadata();
        metadata.setProperty("hidden", new JsNumber(1));
        metadata.setEnumerableProperty("shown", new JsString("v"));
        assertTrue(metadata.hasTable());
        assertEquals(List.of("hidden", "shown"), metadata.propertyKeys());
        assertEquals(List.of("shown"), metadata.enumerablePropertyKeys());
        final var hidden = metadata.getProperty("hidden");
        assertNotNull(hidden);
        assertEquals(1, ((JsNumber) hidden).getValue());
        assertTrue(metadata.deleteProperty("shown"));
        assertFalse(metadata.hasProperty("shown"));
    }

    @Test
    public void test_name_and_length_deletes_are_still_recorded_without_a_table() {
        final var function = new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance());
        assertTrue(function.deleteOwnProperty(new JsString("name")));
        assertTrue(function.callableMetadata().isDeleted("name"));
        assertTrue(function.deleteOwnProperty(new JsString("length")));
        assertTrue(function.callableMetadata().isDeleted("length"));
        assertFalse(function.callableMetadata().hasTable());
    }

    @Test
    public void test_own_properties_hands_out_one_table() {
        final var function = new JsNativeFunction("g", (_, _) -> JsUndefined.getInstance());
        assertSame(function.ownProperties(), function.ownProperties());
        assertTrue(function.callableMetadata().hasTable());
    }
}

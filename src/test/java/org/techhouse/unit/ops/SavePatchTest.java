package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.SavePatch;
import org.techhouse.ops.req.SaveRequest;

public class SavePatchTest {
    private static JsonObject stored() {
        final var nested = new JsonObject();
        nested.add("kept", new JsonString("k"));
        final var document = new JsonObject();
        document.add("_id", new JsonString("d1"));
        document.add("status", new JsonString("pending"));
        document.add("total", new JsonNumber(10));
        document.add("nested", nested);
        return document;
    }

    @Test
    public void test_adds_a_new_field_replaces_an_existing_one_and_keeps_the_rest() {
        final var patch = new JsonObject();
        patch.add("status", new JsonString("expired"));
        patch.add("by", new JsonString("sweeper"));

        final var merged = SavePatch.merged(stored(), patch);

        assertEquals("d1", merged.get("_id").asJsonString().getValue());
        assertEquals("expired", merged.get("status").asJsonString().getValue());
        assertEquals("sweeper", merged.get("by").asJsonString().getValue());
        assertEquals(10, merged.get("total").asJsonNumber().getValue().intValue());
        assertEquals("k", merged.get("nested").asJsonObject().get("kept").asJsonString().getValue());
    }

    @Test
    public void test_a_nested_object_replaces_the_stored_one_whole() {
        final var replacement = new JsonObject();
        replacement.add("fresh", new JsonNumber(1));
        final var patch = new JsonObject();
        patch.add("nested", replacement);

        final var nested = SavePatch.merged(stored(), patch).get("nested").asJsonObject();

        assertEquals(1, nested.size());
        assertFalse(nested.has("kept"));
    }

    @Test
    public void test_a_null_value_is_stored_not_removed() {
        final var patch = new JsonObject();
        patch.add("status", JsonNull.INSTANCE);

        final var merged = SavePatch.merged(stored(), patch);

        assertTrue(merged.has("status"));
        assertTrue(merged.get("status").isJsonNull());
    }

    @Test
    public void test_neither_the_stored_document_nor_the_patch_is_shared_with_the_result() {
        final var document = stored();
        final var patchValue = new JsonObject();
        patchValue.add("x", new JsonNumber(1));
        final var patch = new JsonObject();
        patch.add("extra", patchValue);

        final var merged = SavePatch.merged(document, patch);
        merged.get("nested").asJsonObject().add("added", new JsonNumber(2));

        assertFalse(document.has("extra"));
        assertFalse(document.get("nested").asJsonObject().has("added"));
        assertNotSame(patchValue, merged.get("extra"));
    }

    @Test
    public void test_bulk_save_of_carries_the_names_trigger_depth_and_follow_on_flag() {
        final var request = new SaveRequest("shop", "orders");
        request.setTriggerDepth(3);
        request.setBackgroundFollowOn(true);
        final var documents = List.of(stored());

        final var bulk = SavePatch.bulkSaveOf(request, documents);

        assertEquals("shop", bulk.getDatabaseName());
        assertEquals("orders", bulk.getCollectionName());
        assertEquals(3, bulk.getTriggerDepth());
        assertTrue(bulk.isBackgroundFollowOn());
        assertEquals(documents, bulk.getObjects());
    }
}

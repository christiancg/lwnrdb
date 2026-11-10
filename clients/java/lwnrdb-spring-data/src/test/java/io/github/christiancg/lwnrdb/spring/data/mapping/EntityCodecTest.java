package io.github.christiancg.lwnrdb.spring.data.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.internal.Json;
import io.github.christiancg.lwnrdb.client.mapping.JacksonCodec;
import io.github.christiancg.lwnrdb.spring.data.model.Address;
import io.github.christiancg.lwnrdb.spring.data.model.Plain;
import io.github.christiancg.lwnrdb.spring.data.model.Widget;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class EntityCodecTest {
    private static final LwnrdbMappingContext CONTEXT = new LwnrdbMappingContext("shop");

    @SuppressWarnings("unchecked")
    private static <T> EntityCodec<T> codec(Class<T> type) {
        return new EntityCodec<>(new JacksonCodec<>(type, Json.defaults()),
                (LwnrdbPersistentEntity<T>) CONTEXT.getRequiredPersistentEntity(type));
    }

    @Test
    public void test_an_annotated_id_is_stored_as_the_primary_key_and_read_back() {
        final var codec = codec(Widget.class);
        final var widget = new Widget("w1", "bolt", 2.5, "Bolt", new Address("Lima"), "dropped");
        final var document = codec.encode(widget);
        assertEquals("w1", document.id().orElseThrow());
        assertFalse(document.has("code"));
        assertFalse(document.has("scratch"));
        assertEquals("Bolt", document.getString("label").orElseThrow());
        final var read = codec.decode(document);
        assertEquals("w1", read.code());
        assertNull(read.scratch());
        assertEquals(widget.address(), read.address());
        assertEquals("w1", codec.idOf(widget).orElseThrow());
    }

    @Test
    public void test_a_missing_id_is_generated_and_an_existing_one_is_kept() {
        final var codec = codec(Widget.class);
        final var withId = Widget.of("keep", "a", 1);
        assertSame(withId, codec.withGeneratedId(withId));
        final var generated = codec.withGeneratedId(Widget.of(null, "a", 1));
        assertEquals(36, generated.code().length());
        assertEquals(generated.code(), UUID.fromString(generated.code()).toString());
        assertTrue(codec.idOf(Widget.of(null, "a", 1)).isEmpty());
        assertFalse(codec.encode(Widget.of(null, "a", 1)).has("code"));
    }

    @Test
    public void test_a_property_named_id_uses_the_clients_alias() {
        final var codec = codec(Plain.class);
        final var plain = new Plain();
        plain.setId("p1");
        plain.setName("n");
        final var document = codec.encode(plain);
        assertEquals("p1", document.id().orElseThrow());
        assertEquals("p1", codec.decode(document).getId());
        final var unnamed = new Plain();
        assertEquals(36, codec.withGeneratedId(unnamed).getId().length());
    }
}

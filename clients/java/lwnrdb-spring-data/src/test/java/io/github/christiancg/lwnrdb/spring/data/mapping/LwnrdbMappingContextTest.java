package io.github.christiancg.lwnrdb.spring.data.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.spring.data.model.Invalid;
import io.github.christiancg.lwnrdb.spring.data.model.Plain;
import io.github.christiancg.lwnrdb.spring.data.model.Widget;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.mapping.MappingException;

public class LwnrdbMappingContextTest {
    private final LwnrdbMappingContext context = new LwnrdbMappingContext("fallback");

    private LwnrdbPersistentEntity<?> entity(Class<?> type) {
        return context.getRequiredPersistentEntity(type);
    }

    private String path(String path, Class<?> type) {
        return context.getPersistentPropertyPath(path, type).stream().map(LwnrdbPersistentProperty::wireName)
                .collect(Collectors.joining("."));
    }

    @Test
    public void test_the_id_comes_from_the_annotation_or_a_property_named_id() {
        assertEquals("code", entity(Widget.class).requiredIdProperty().getName());
        assertEquals("code", entity(Widget.class).idJavaWireName());
        assertEquals("id", entity(Plain.class).requiredIdProperty().getName());
        assertEquals("_id", path("code", Widget.class));
        assertEquals("_id", path("id", Plain.class));
    }

    @Test
    public void test_collection_and_database_names() {
        assertEquals("widgets", entity(Widget.class).collection());
        assertEquals("shop", entity(Widget.class).database());
        assertEquals("plain", entity(Plain.class).collection());
        assertEquals("fallback", entity(Plain.class).database());
        assertEquals("fallback", context.defaultDatabase());
        assertThrows(MappingException.class, () -> entity(Invalid.ShortCollection.class).collection());
        assertThrows(MappingException.class,
                () -> new LwnrdbMappingContext(null).getRequiredPersistentEntity(Invalid.NoDatabase.class).database());
        assertThrows(MappingException.class,
                () -> new LwnrdbMappingContext("").getRequiredPersistentEntity(Invalid.NoDatabase.class).database());
    }

    @Test
    public void test_wire_names_follow_json_property_and_nest_with_dots() {
        assertEquals("label", path("title", Widget.class));
        assertEquals("address.city", path("address.city", Widget.class));
        assertEquals("name", path("name", Widget.class));
    }

    @Test
    public void test_transient_fields_are_listed_and_not_persistent() {
        assertEquals(List.of("scratch"), entity(Widget.class).transientWireNames());
        assertTrue(entity(Widget.class).isTransient("scratch"));
    }

    @Test
    public void test_invalid_ids_fail() {
        assertThrows(MappingException.class, () -> entity(Invalid.NumericId.class));
        assertThrows(MappingException.class, () -> entity(Invalid.TwoIds.class));
        assertThrows(MappingException.class, () -> entity(Invalid.NoId.class).requiredIdProperty());
    }
}

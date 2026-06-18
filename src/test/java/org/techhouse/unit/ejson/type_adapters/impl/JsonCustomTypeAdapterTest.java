package org.techhouse.unit.ejson.type_adapters.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;
import org.techhouse.ejson.type_adapters.impl.JsonCustomTypeAdapter;

public class JsonCustomTypeAdapterTest {

    // Passing null value to toJson method
    @Test
    public void test_to_json_with_null_value() {
        // Arrange
        JsonCustomTypeAdapter adapter = new JsonCustomTypeAdapter();

        // Act & Assert
        assertThrows(NullPointerException.class, () -> adapter.toJson(null));
    }

    // Input is a JsonCustom instance and returns correctly converted JsonCustom object
    @Test
    public void test_json_custom_conversion() {
        JsonCustomTypeAdapter adapter = new JsonCustomTypeAdapter();

        JsonCustom<?> mockCustom = mock(JsonCustom.class);
        //noinspection unchecked
        TypeAdapter<JsonBaseElement> mockBaseAdapter = mock(TypeAdapter.class);

        try (MockedStatic<TypeAdapterFactory> factory = mockStatic(TypeAdapterFactory.class)) {
            factory.when(() -> TypeAdapterFactory.getAdapter(JsonBaseElement.class)).thenReturn(mockBaseAdapter);

            when(mockBaseAdapter.fromJson(mockCustom)).thenReturn(mockCustom);

            JsonCustom<?> result = adapter.fromJson(mockCustom);

            assertNotNull(result);
            assertEquals(mockCustom, result);
        }
    }

    // toJson on a real JsonCustom object calls toJson on base adapter (L12)
    @Test
    public void test_to_json_with_real_custom_type() {
        new org.techhouse.ejson.EJson(); // register custom types
        JsonCustomTypeAdapter adapter = new JsonCustomTypeAdapter();
        JsonTime time = new JsonTime("#time(10:00:00)");
        String result = adapter.toJson(time);
        assertNotNull(result);
        assertTrue(result.contains("10:00:00"));
    }

    // fromJson with a non-JsonCustom element returns null (L20)
    @Test
    public void test_from_json_with_non_custom_returns_null() {
        JsonCustomTypeAdapter adapter = new JsonCustomTypeAdapter();
        JsonCustom<?> result = adapter.fromJson(JsonNull.INSTANCE);
        assertNull(result);
    }
}

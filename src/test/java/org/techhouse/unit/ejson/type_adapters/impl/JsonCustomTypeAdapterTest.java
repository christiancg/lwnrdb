package org.techhouse.unit.ejson.type_adapters.impl;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;
import org.techhouse.ejson.type_adapters.impl.JsonCustomTypeAdapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
        TypeAdapter<JsonBaseElement> mockBaseAdapter = mock(TypeAdapter.class);

        try (MockedStatic<TypeAdapterFactory> factory = mockStatic(TypeAdapterFactory.class)) {
            factory.when(() -> TypeAdapterFactory.getAdapter(JsonBaseElement.class))
                    .thenReturn(mockBaseAdapter);

            when(mockBaseAdapter.fromJson(mockCustom))
                    .thenReturn(mockCustom);

            JsonCustom<?> result = adapter.fromJson(mockCustom);

            assertNotNull(result);
            assertEquals(mockCustom, result);
        }
    }
}
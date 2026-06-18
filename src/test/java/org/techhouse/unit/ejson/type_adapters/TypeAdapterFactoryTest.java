package org.techhouse.unit.ejson.type_adapters;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonSyntaxToken;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;
import org.techhouse.ejson.type_adapters.impl.ReflectionTypeAdapter;

public class TypeAdapterFactoryTest {
    // Register and retrieve a basic type adapter for a simple class
    @Test
    public void test_register_and_retrieve_type_adapter() {
        TypeAdapter<String> stringAdapter = new TypeAdapter<>() {
            @Override
            public String toJson(String value) {
                return "\"" + value + "\"";
            }

            @Override
            public String fromJson(JsonBaseElement value) {
                return value.asJsonString().getValue();
            }
        };

        TypeAdapterFactory.registerTypeAdapter(String.class, stringAdapter);

        TypeAdapter<String> retrievedAdapter = TypeAdapterFactory.getAdapter(String.class);

        assertNotNull(retrievedAdapter);
        assertEquals("\"test\"", retrievedAdapter.toJson("test"));
    }

    // Register new type adapter for a class type
    @Test
    public void register_type_adapter_for_class() {
        class TestClass {
        }

        TypeAdapter<TestClass> adapter = new TypeAdapter<>() {
            @Override
            public String toJson(TestClass value) {
                return "{}";
            }

            @Override
            public TestClass fromJson(JsonBaseElement value) {
                return new TestClass();
            }
        };

        TypeAdapterFactory.registerTypeAdapter(TestClass.class, adapter);

        TypeAdapter<?> retrievedAdapter = TypeAdapterFactory.getAdapter(TestClass.class);

        assertNotNull(retrievedAdapter);
        assertEquals(adapter, retrievedAdapter);
    }

    // Returns cached adapter when type exists in _genericTypeAdapters map
    @Test
    public void test_returns_cached_adapter_when_type_exists() throws ClassNotFoundException {
        // Create a parameterized type for List<String>
        Type listType = new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[]{String.class};
            }

            @Override
            public Type getRawType() {
                return List.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };

        // Get adapter first time to cache it
        TypeAdapter<?> firstCall = TypeAdapterFactory.getAdapter(listType);

        // Get adapter second time - should return cached instance
        TypeAdapter<?> secondCall = TypeAdapterFactory.getAdapter(listType);

        assertNotNull(secondCall);
        assertSame(firstCall, secondCall);
    }

    // Handles null input type parameter
    @Test
    public void test_handles_null_type_parameter() {
        assertThrows(NullPointerException.class, () -> TypeAdapterFactory.getAdapter((Type) null));
    }

    // Returns cached adapter when type exists in _adapters map
    @Test
    public void test_returns_cached_adapter_when_exists() {
        TypeAdapter<String> stringAdapter = new ReflectionTypeAdapter<>(String.class);
        TypeAdapterFactory.registerTypeAdapter(String.class, stringAdapter);

        TypeAdapter<String> result = TypeAdapterFactory.getAdapter(String.class);

        assertSame(stringAdapter, result);
    }

    // getAdapter(Class) for a JsonCustom subclass returns a JsonCustomTypeAdapter (L51-53)
    @Test
    public void test_get_adapter_for_custom_type() {
        new org.techhouse.ejson.EJson(); // register custom types
        TypeAdapter<JsonTime> adapter = TypeAdapterFactory.getAdapter(JsonTime.class);
        assertNotNull(adapter);
    }

    // JsonBaseElementTypeAdapter.toJson with a syntax token throws (L23 default case)
    @Test
    public void test_json_base_element_adapter_syntax_token_throws() {
        TypeAdapter<JsonBaseElement> adapter = TypeAdapterFactory.getAdapter(JsonBaseElement.class);
        assertThrows(IllegalStateException.class, () -> adapter.toJson(JsonSyntaxToken.COMMA));
    }
}

package org.techhouse.unit.ejson.type_adapters;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonSyntaxToken;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;
import org.techhouse.ejson.type_adapters.impl.ReflectionTypeAdapter;

public class TypeAdapterFactoryTest {
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

    static List<String> listOfStrings() {
        return List.of();
    }

    @Test
    public void test_returns_cached_adapter_when_type_exists() throws Exception {
        Type listType = TypeAdapterFactoryTest.class.getDeclaredMethod("listOfStrings").getGenericReturnType();

        TypeAdapter<?> firstCall = TypeAdapterFactory.getAdapter(listType);

        TypeAdapter<?> secondCall = TypeAdapterFactory.getAdapter(listType);

        assertNotNull(secondCall);
        assertSame(firstCall, secondCall);
    }

    @Test
    public void test_handles_null_type_parameter() {
        assertThrows(NullPointerException.class, () -> TypeAdapterFactory.getAdapter((Type) null));
    }

    @Test
    public void test_returns_cached_adapter_when_exists() {
        TypeAdapter<String> stringAdapter = new ReflectionTypeAdapter<>(String.class);
        TypeAdapterFactory.registerTypeAdapter(String.class, stringAdapter);

        TypeAdapter<String> result = TypeAdapterFactory.getAdapter(String.class);

        assertSame(stringAdapter, result);
    }

    @Test
    public void test_get_adapter_for_custom_type() {
        new org.techhouse.ejson.EJson(); // register custom types
        TypeAdapter<JsonTime> adapter = TypeAdapterFactory.getAdapter(JsonTime.class);
        assertNotNull(adapter);
    }

    @Test
    public void test_json_base_element_adapter_syntax_token_throws() {
        TypeAdapter<JsonBaseElement> adapter = TypeAdapterFactory.getAdapter(JsonBaseElement.class);
        assertThrows(IllegalStateException.class, () -> adapter.toJson(JsonSyntaxToken.COMMA));
    }

    @Test
    public void test_a_registered_adapter_is_returned_without_computing() {
        final class Registered {
        }
        final TypeAdapter<Registered> adapter = new TypeAdapter<>() {
            @Override
            public String toJson(Registered value) {
                return "{}";
            }

            @Override
            public Registered fromJson(JsonBaseElement value) {
                return new Registered();
            }
        };

        TypeAdapterFactory.registerTypeAdapter(Registered.class, adapter);

        assertSame(adapter, TypeAdapterFactory.getAdapter(Registered.class));
        assertSame(adapter, TypeAdapterFactory.getAdapter(Registered.class));
    }

    @Test
    public void test_an_unknown_class_gets_one_reflection_adapter_under_concurrency() throws Exception {
        final class Fresh {
        }
        final var threads = 16;
        final var start = new CountDownLatch(1);
        final var adapters = Collections
                .synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<TypeAdapter<?>, Boolean>()));
        final var workers = new ArrayList<Thread>();
        for (var i = 0; i < threads; i++) {
            workers.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                adapters.add(TypeAdapterFactory.getAdapter(Fresh.class));
            }));
        }
        start.countDown();
        for (final var worker : workers) {
            worker.join();
        }

        assertEquals(1, adapters.size());
        assertInstanceOf(ReflectionTypeAdapter.class, adapters.iterator().next());
    }
}

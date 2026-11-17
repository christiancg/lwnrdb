package org.techhouse.unit.ejson.type_adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;
import org.techhouse.ejson.type_adapters.impl.StringTypeAdapter;

public class TypeAdapterFactoryIsolationTest {
    private static final String QUOTED = "{\"type\":\"SAVE\"}";
    private static final String ESCAPED = "\"{\\\"type\\\":\\\"SAVE\\\"}\"";

    @AfterEach
    public void restoreTheShippedStringAdapter() {
        TypeAdapterFactory.registerTypeAdapter(String.class, new StringTypeAdapter());
    }

    private static List<Method> annotated(Class<? extends Annotation> marker) {
        return Arrays.stream(TypeAdapterFactoryTest.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(marker)).sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    private static boolean stringsAreEscaped() {
        try {
            return ESCAPED.equals(TypeAdapterFactory.getAdapter(String.class).toJson(QUOTED));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    public void test_no_type_adapter_factory_test_leaves_strings_serialised_unescaped() throws Exception {
        new EJson();
        final var tests = annotated(Test.class);
        final var cleanups = annotated(AfterEach.class);
        final var leaking = new ArrayList<String>();
        for (final var test : tests) {
            final var instance = new TypeAdapterFactoryTest();
            try {
                test.invoke(instance);
            } finally {
                for (final var cleanup : cleanups) {
                    cleanup.invoke(instance);
                }
            }
            if (!stringsAreEscaped()) {
                leaking.add(test.getName());
            }
        }

        assertEquals(List.of(), leaking,
                "a String adapter left registered is bound into every class planned afterwards in the same fork,"
                        + " so a peer frame carrying a JSON body goes out unescaped");
    }
}

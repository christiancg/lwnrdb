package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.UnsafeAllocator;

public class UnsafeAllocatorTest {
    @Test
    public void test_creates_simple_class() throws Exception {
        @SuppressWarnings("unused")
        class TestClass {
            private final String testField = "initial";
        }
        final var instance = UnsafeAllocator.INSTANCE.newInstance(TestClass.class);
        assertNotNull(instance);
    }

    @Test
    public void test_creates_class_with_constructor() throws Exception {
        record TestClassWithConstructor(String testField) {
        }
        final var instance = UnsafeAllocator.INSTANCE.newInstance(TestClassWithConstructor.class);
        assertNotNull(instance);
    }

    @Test
    public void test_instance_is_not_null() {
        final var instance = UnsafeAllocator.INSTANCE;
        assertNotNull(instance);
    }

    static final class Tracked {
        String value;

        Tracked() {
            value = "set by the constructor";
        }
    }

    @Test
    public void test_allocation_through_the_bound_handle_skips_the_constructor() throws Exception {
        assertNotNull(new Tracked().value);

        final var instance = UnsafeAllocator.INSTANCE.newInstance(Tracked.class);

        assertNotNull(instance);
        assertNull(instance.value);
    }
}

package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.UnsafeAllocator;

public class UnsafeAllocatorTest {
    @Test
    public void test_creates_simple_class() throws Exception {
        class TestClass {
            private String testField = "initial"; // NOPMD - reflection/serialization test fixture
            public String getTestField() {
                return testField;
            }
            public void setTestField(String testField) {
                this.testField = testField;
            }
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
}

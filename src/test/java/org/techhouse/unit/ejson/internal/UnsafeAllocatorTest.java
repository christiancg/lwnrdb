package org.techhouse.unit.ejson.internal;

import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.UnsafeAllocator;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class UnsafeAllocatorTest {
    @Test
    public void test_creates_simple_class() throws Exception {
        @Getter
        @Setter
        class TestClass {
            private String testField = "initial";
        }
        final var instance = UnsafeAllocator.INSTANCE.newInstance(TestClass.class);
        assertNotNull(instance);
    }

    @Test
    public void test_creates_class_with_constructor() throws Exception {
        @Getter
        @Setter
        class TestClassWithConstructor {
            private String testField;
            public TestClassWithConstructor(String testField) {
                this.testField = testField;
            }
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

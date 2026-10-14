package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBoolean;

public class JsonBooleanTest {
    @Test
    public void test_false_singleton_holds_false() {
        assertFalse(JsonBoolean.FALSE.getValue());
    }

    @Test
    public void test_true_singleton_holds_true() {
        assertTrue(JsonBoolean.TRUE.getValue());
    }

    @Test
    public void test_of_returns_the_singletons() {
        assertSame(JsonBoolean.TRUE, JsonBoolean.of(true));
        assertSame(JsonBoolean.FALSE, JsonBoolean.of(false));
    }
}

package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBoolean;

public class JsonBooleanTest {
    @Test
    public void test_default_constructor_sets_false() {
        JsonBoolean jsonBoolean = new JsonBoolean();

        assertFalse(jsonBoolean.getValue());
    }

    @Test
    public void test_constructor_accepts_null_value() {
        JsonBoolean jsonBoolean = new JsonBoolean(null);

        assertNull(jsonBoolean.getValue());
    }
}

package org.techhouse.unit.ejson.elements;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;

import static org.junit.jupiter.api.Assertions.*;

public class JsonNullTest {
    // Singleton instance INSTANCE is accessible and returns same object on multiple accesses
    @Test
    public void test_singleton_instance_returns_same_object() {
        JsonNull instance1 = JsonNull.INSTANCE;
        JsonNull instance2 = JsonNull.INSTANCE;
    
        assertSame(instance1, instance2);
    }

    // equals() returns false when comparing with null
    @Test
    public void test_equals_returns_false_for_null() {
        JsonNull jsonNull = JsonNull.INSTANCE;

        assertNotEquals(null, jsonNull);
    }

    // Returns consistent hash code for all JsonNull instances
    @Test
    public void test_consistent_hashcode_for_all_instances() {
        JsonNull instance1 = JsonNull.INSTANCE;
        JsonNull instance2 = JsonNull.INSTANCE;
        JsonNull instance3 = new JsonNull();

        int hashCode1 = instance1.hashCode();
        int hashCode2 = instance2.hashCode();
        int hashCode3 = instance3.hashCode();

        assertEquals(hashCode1, hashCode2);
        assertEquals(hashCode2, hashCode3);
        assertEquals(hashCode1, JsonNull.class.hashCode());
    }

    // Compare JsonNull instance with another JsonNull instance returns true
    @Test
    public void test_json_null_equals_another_json_null() {
        JsonNull jsonNull1 = JsonNull.INSTANCE;
        JsonNull jsonNull2 = JsonNull.INSTANCE;

        boolean result = jsonNull1.equals(jsonNull2);

        assertTrue(result);
    }

    // Compare JsonNull with null returns false
    @Test
    public void test_json_null_equals_null() {
        JsonNull jsonNull = JsonNull.INSTANCE;

        boolean result = jsonNull.equals(null);

        assertFalse(result);
    }

    // Returns singleton INSTANCE when deepCopy is called
    @Test
    public void test_deep_copy_returns_singleton_instance() {
        JsonNull jsonNull = new JsonNull();

        JsonBaseElement result = jsonNull.deepCopy();

        assertSame(JsonNull.INSTANCE, result);
    }

    // Verify INSTANCE is not null
    @Test
    public void test_instance_not_null() {
        JsonNull jsonNull = JsonNull.INSTANCE;

        assertNotNull(jsonNull);

        JsonBaseElement copy = jsonNull.deepCopy();
        assertNotNull(copy);
    }
}
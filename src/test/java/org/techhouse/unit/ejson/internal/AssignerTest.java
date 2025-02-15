package org.techhouse.unit.ejson.internal;

import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.Assigner;

import static org.junit.jupiter.api.Assertions.*;

public class AssignerTest {
    @Getter
    @Setter
    static class Person {
        private String name;
        private int age;
    }

    // Assign JsonObject to a simple POJO class using reflection adapter
    @Test
    public void test_assign_json_to_pojo() {
        new EJson();
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("name", "John");
        jsonObject.addProperty("age", 25);

        Person person = Assigner.assign(jsonObject, Person.class);

        assertNotNull(person);
        assertEquals("John", person.getName());
        assertEquals(25, person.getAge());
    }

    // Handle null input JsonObject
    @Test
    public void test_assign_null_json() {
        assertThrows(NullPointerException.class, () -> Assigner.assign(null, Person.class));
    }
}
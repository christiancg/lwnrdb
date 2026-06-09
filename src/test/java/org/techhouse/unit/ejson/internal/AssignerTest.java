package org.techhouse.unit.ejson.internal;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.Assigner;

import static org.junit.jupiter.api.Assertions.*;

public class AssignerTest {
    static class Person {
        private String name;
        private int age;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
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

    // Instantiating Assigner (covers implicit default constructor for coverage)
    @Test
    public void test_assigner_instantiation() {
        assertNotNull(new Assigner());
    }
}
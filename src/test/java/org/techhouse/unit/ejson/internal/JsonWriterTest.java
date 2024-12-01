package org.techhouse.unit.ejson.internal;

import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.internal.JsonWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JsonWriterTest {
    @BeforeEach
    public void setUp() {
        new EJson();
    }
    // Convert simple POJO to JSON string using reflection adapter
    @Test
    public void test_convert_pojo_to_json_string() {
        @Getter
        @Setter
        class TestPojo {
            private String name;
            private int value;
        }
        JsonWriter writer = new JsonWriter();
        TestPojo pojo = new TestPojo();
        pojo.setName("test");
        pojo.setValue(123);

        String json = writer.toJson(pojo, TestPojo.class);

        assertEquals("{\"name\":\"test\",\"value\":123}", json);
    }

    // Handle null input object
    @Test
    public void test_convert_null_to_json_string() {
        JsonWriter writer = new JsonWriter();
        String json = writer.toJson(null, String.class);
    
        assertEquals("null", json);
    }

    // Convert a simple POJO to JSON string using ReflectionTypeAdapter
    @Test
    public void test_convert_simple_pojo_to_json() {
        class TestPojo {
            public String stringField;
            public int intField;
        }
        JsonWriter writer = new JsonWriter();

        TestPojo pojo = new TestPojo();
        pojo.stringField = "test";
        pojo.intField = 42;

        String json = writer.toJson(pojo, TestPojo.class);

        assertEquals("{\"stringField\":\"test\",\"intField\":42}", json);
    }
}
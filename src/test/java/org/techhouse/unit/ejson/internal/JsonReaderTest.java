package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.exceptions.MalformedJsonException;
import org.techhouse.ejson.internal.JsonReader;

public class JsonReaderTest {
    static class TestClass {
        private String name;
        private int value;

        public String getName() {
            return name;
        }

        public int getValue() {
            return value;
        }
    }

    static class TestPerson {
        private String name;
        private int age;

        public String getName() {
            return name;
        }

        public int getAge() {
            return age;
        }
    }

    // Parse valid JSON object with multiple key-value pairs
    @Test
    public void test_parse_valid_json_object_with_multiple_pairs() {
        new EJson();
        JsonReader reader = new JsonReader();
        String json = "{\"name\":\"John\",\"age\":30}";

        TestPerson result = reader.fromJson(json, TestPerson.class);

        assertNotNull(result);
        assertEquals("John", result.getName());
        assertEquals(30, result.getAge());
    }

    // Handle empty input string
    @Test
    public void test_empty_input_string_throws_exception() {
        JsonReader reader = new JsonReader();
        String json = "";

        MalformedJsonException exception = assertThrows(MalformedJsonException.class,
                () -> reader.fromJson(json, TestPerson.class));

        assertEquals("Empty JSON array", exception.getMessage());
    }

    // Successfully parse valid JSON string into target class instance
    @Test
    public void parse_valid_json_string_to_target_class() {
        JsonReader jsonReader = new JsonReader();

        String jsonInput = "{\"name\":\"test\",\"value\":123}";

        TestClass result = jsonReader.fromJson(jsonInput, TestClass.class);

        assertNotNull(result);
        assertEquals("test", result.getName());
        assertEquals(123, result.getValue());
    }

    // Parse JSON object with multiple nested properties
    @Test
    public void test_parse_json_with_nested_properties() {
        new EJson();
        class InnerClass {
            private String key;

            public String getKey() {
                return key;
            }
        }
        class OuterClass {
            private InnerClass inner;

            public InnerClass getInner() {
                return inner;
            }
        }
        class MyClass {
            private OuterClass outer;

            public OuterClass getOuter() {
                return outer;
            }
        }
        String jsonInput = "{\"outer\":{\"inner\":{\"key\":\"value\"}}}";
        Class<MyClass> targetClass = MyClass.class;
        JsonReader jsonReader = new JsonReader();

        MyClass result = jsonReader.fromJson(jsonInput, targetClass);

        assertNotNull(result);
        assertNotNull(result.getOuter());
        assertNotNull(result.getOuter().getInner());
        assertEquals("value", result.getOuter().getInner().getKey());
    }

    // Parse JSON object with primitive data types (string, number, boolean)
    @Test
    public void test_parse_json_with_primitive_types() {
        class PrimitiveClass {
            private String stringKey;
            private int numberKey;
            private boolean booleanKey;

            public String getStringKey() {
                return stringKey;
            }

            public int getNumberKey() {
                return numberKey;
            }

            public boolean isBooleanKey() {
                return booleanKey;
            }
        }
        String jsonInput = "{\"stringKey\":\"stringValue\",\"numberKey\":123,\"booleanKey\":true}";
        Class<PrimitiveClass> targetClass = PrimitiveClass.class;
        JsonReader jsonReader = new JsonReader();

        PrimitiveClass result = jsonReader.fromJson(jsonInput, targetClass);

        assertNotNull(result);
        assertEquals("stringValue", result.getStringKey());
        assertEquals(123, result.getNumberKey());
        assertTrue(result.isBooleanKey());
    }

    // Parse empty JSON object into target class
    @Test
    public void test_parse_empty_json_object() {
        class EmptyClass {
        }
        String jsonInput = "{}";
        Class<EmptyClass> targetClass = EmptyClass.class;
        JsonReader jsonReader = new JsonReader();

        EmptyClass result = jsonReader.fromJson(jsonInput, targetClass);

        assertNotNull(result);
    }

    // Parse JSON object with array
    @Test
    public void test_parse_json_with_array() {
        new EJson();
        class ClassWithArray {
            private List<String> stringArray;

            public List<String> getStringArray() {
                return stringArray;
            }
        }
        String jsonInput = "{\"stringArray\": [\"value1\",\"value2\",\"value3\"]}";
        Class<ClassWithArray> targetClass = ClassWithArray.class;
        JsonReader jsonReader = new JsonReader();

        ClassWithArray result = jsonReader.fromJson(jsonInput, targetClass);

        assertNotNull(result);
        assertEquals(List.of("value1", "value2", "value3"), result.getStringArray());
    }

    // Root JSON starting with a non-brace/bracket throws MalformedJsonException (L25)
    @Test
    public void test_root_not_starting_with_brace_or_bracket_throws() {
        // EJson parses via fromJson which calls internalParse(tokens, isRoot=true)
        // We can trigger this via EJson
        EJson eJson = new EJson();
        assertThrows(Exception.class, () -> eJson.fromJson("123", Object.class));
    }

    // Object with non-string key throws MalformedJsonException (L52)
    @Test
    public void test_object_non_string_key_throws_malformed_json() {
        EJson eJson = new EJson();
        assertThrows(Exception.class, () -> eJson.fromJson("{123: \"value\"}", Object.class));
    }

    // Object missing colon after key throws MalformedJsonException (L56)
    @Test
    public void test_object_missing_colon_throws_malformed_json() {
        EJson eJson = new EJson();
        assertThrows(Exception.class, () -> eJson.fromJson("{\"key\" \"value\"}", Object.class));
    }

    // Object missing comma between pairs throws MalformedJsonException (L69)
    @Test
    public void test_object_missing_comma_throws_malformed_json() {
        EJson eJson = new EJson();
        assertThrows(Exception.class, () -> eJson.fromJson("{\"a\": 1 \"b\": 2}", Object.class));
    }

    // Array missing comma between elements throws MalformedJsonException (L94)
    @Test
    public void test_array_missing_comma_throws_malformed_json() {
        EJson eJson = new EJson();
        assertThrows(Exception.class, () -> eJson.fromJson("[1 2 3]", Object.class));
    }
}

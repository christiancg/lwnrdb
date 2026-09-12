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

    @Test
    public void test_empty_input_string_throws_exception() {
        JsonReader reader = new JsonReader();
        String json = "";

        MalformedJsonException exception = assertThrows(MalformedJsonException.class,
                () -> reader.fromJson(json, TestPerson.class));

        assertEquals("Empty JSON array", exception.getMessage());
    }

    @Test
    public void parse_valid_json_string_to_target_class() {
        JsonReader jsonReader = new JsonReader();

        String jsonInput = "{\"name\":\"test\",\"value\":123}";

        TestClass result = jsonReader.fromJson(jsonInput, TestClass.class);

        assertNotNull(result);
        assertEquals("test", result.getName());
        assertEquals(123, result.getValue());
    }

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

    @Test
    public void test_root_not_starting_with_brace_or_bracket_throws() {
        EJson eJson = new EJson();
        assertThrows(Exception.class, () -> eJson.fromJson("123", Object.class));
    }

    @Test
    public void test_object_non_string_key_throws_malformed_json() {
        EJson eJson = new EJson();
        assertThrows(Exception.class, () -> eJson.fromJson("{123: \"value\"}", Object.class));
    }

    @Test
    public void test_object_missing_colon_throws_malformed_json() {
        EJson eJson = new EJson();
        assertThrows(Exception.class, () -> eJson.fromJson("{\"key\" \"value\"}", Object.class));
    }

    @Test
    public void test_object_missing_comma_throws_malformed_json() {
        EJson eJson = new EJson();
        assertThrows(Exception.class, () -> eJson.fromJson("{\"a\": 1 \"b\": 2}", Object.class));
    }

    @Test
    public void test_array_missing_comma_throws_malformed_json() {
        EJson eJson = new EJson();
        assertThrows(Exception.class, () -> eJson.fromJson("[1 2 3]", Object.class));
    }
}

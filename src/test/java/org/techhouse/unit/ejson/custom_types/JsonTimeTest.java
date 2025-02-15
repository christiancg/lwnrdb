package org.techhouse.unit.ejson.custom_types;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonTime;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

public class JsonTimeTest {
    // Create JsonTime with valid LocalTime object
    @Test
    public void create_json_time_with_valid_local_time() {
        LocalTime time = LocalTime.of(10, 30, 15);
        JsonTime jsonTime = new JsonTime(time);
        assertEquals(time, jsonTime.getCustomValue());
    }

    // Constructor accepts valid time string in format 'time(HH:mm:ss)'
    @Test
    public void test_constructor_accepts_valid_time_string() {
        String validTimeStr = "#time(13:45:30)";

        JsonTime jsonTime = new JsonTime(validTimeStr);

        assertEquals(validTimeStr, jsonTime.getValue());
        assertEquals(LocalTime.of(13, 45, 30), jsonTime.getCustomValue());
    }

    // Default constructor creates empty JsonTime instance
    @Test
    public void test_default_constructor_creates_empty_instance() {
        JsonTime jsonTime = new JsonTime();

        assertNotNull(jsonTime);
        assertEquals("", jsonTime.getValue());
    }

    // Default constructor creates object with null customValue
    @Test
    public void test_default_constructor_creates_null_custom_value() {
        JsonTime jsonTime = new JsonTime();

        assertNull(jsonTime.getCustomValue());
    }

    // Returns string "time" when called
    @Test
    public void returns_time_string_when_called() {
        JsonTime jsonTime = new JsonTime(LocalTime.now());

        String result = jsonTime.getCustomTypeName();

        assertEquals("time", result);
    }

    // Method behavior when class is subclassed
    @Test
    public void subclass_returns_same_type_name() {
        class CustomJsonTime extends JsonTime {
            public CustomJsonTime(LocalTime time) {
                super(time);
            }
        }

        CustomJsonTime customTime = new CustomJsonTime(LocalTime.now());

        String result = customTime.getCustomTypeName();

        assertEquals("time", result);
    }

    // Compare two different times returns correct ordering (-1, 0, or 1)
    @Test
    public void test_compare_returns_correct_ordering() {
        JsonTime earlier = new JsonTime(LocalTime.of(10, 30));
        JsonTime later = new JsonTime(LocalTime.of(11, 30));

        assertEquals(-1, earlier.compare(later.getCustomValue()));
        assertEquals(1, later.compare(earlier.getCustomValue()));
        assertEquals(0, earlier.compare(earlier.getCustomValue()));
    }

    // Compare with null time parameter throws NullPointerException
    @Test
    public void test_compare_with_null_throws_exception() {
        JsonTime time = new JsonTime(LocalTime.of(10, 30));

        assertThrows(NullPointerException.class, () -> time.compare(null));
    }
}
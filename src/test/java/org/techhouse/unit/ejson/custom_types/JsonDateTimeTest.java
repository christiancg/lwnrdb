package org.techhouse.unit.ejson.custom_types;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonDateTime;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

public class JsonDateTimeTest {
    // Create JsonDateTime with valid LocalDateTime object
    @Test
    public void test_create_with_valid_local_date_time() {
        LocalDateTime dateTime = LocalDateTime.of(2023, 12, 25, 10, 30);
    
        JsonDateTime jsonDateTime = new JsonDateTime("#datetime(2023-12-25T10:30:00)");

        assertEquals(dateTime, jsonDateTime.getCustomValue());
        assertEquals("2023-12-25T10:30:00", jsonDateTime.stringDataValue());
    }

    // Constructor successfully creates JsonDateTime instance with valid LocalDateTime
    @Test
    public void test_string_constructor_with_valid_local_date_time() {
        LocalDateTime now = LocalDateTime.now();
        JsonDateTime jsonDateTime = new JsonDateTime("#datetime(" + now + ")");

        assertNotNull(jsonDateTime);
        assertEquals(now, jsonDateTime.getCustomValue());
        assertTrue(jsonDateTime.getValue().startsWith("#datetime("));
        assertTrue(jsonDateTime.getValue().endsWith(")"));
    }

    // Constructor handles minimum allowed LocalDateTime value
    @Test
    public void test_string_constructor_with_min_local_date_time() {
        LocalDateTime minDateTime = LocalDateTime.MIN;
        JsonDateTime jsonDateTime = new JsonDateTime("#datetime(" + minDateTime + ")");

        assertNotNull(jsonDateTime);
        assertEquals(minDateTime, jsonDateTime.getCustomValue());
        assertTrue(jsonDateTime.getValue().startsWith("#datetime("));
        assertTrue(jsonDateTime.getValue().endsWith(")"));
    }

    // Constructor successfully creates JsonDateTime instance with valid LocalDateTime
    @Test
    public void test_constructor_with_valid_local_date_time() {
        LocalDateTime now = LocalDateTime.now();
        JsonDateTime jsonDateTime = new JsonDateTime(now);

        assertNotNull(jsonDateTime);
        assertEquals(now, jsonDateTime.getCustomValue());
    }

    // Constructor handles minimum allowed LocalDateTime value
    @Test
    public void test_constructor_with_min_local_date_time() {
        LocalDateTime minDateTime = LocalDateTime.MIN;
        JsonDateTime jsonDateTime = new JsonDateTime(minDateTime);

        assertNotNull(jsonDateTime);
        assertEquals(minDateTime, jsonDateTime.getCustomValue());
    }

    // Default constructor creates empty JsonDateTime instance
    @Test
    public void test_default_constructor_creates_empty_instance() {
        JsonDateTime dateTime = new JsonDateTime();

        assertNotNull(dateTime);
        assertEquals("", dateTime.getValue());
    }

    // Default constructor creates instance with null customValue
    @Test
    public void test_default_constructor_creates_null_custom_value() {
        JsonDateTime dateTime = new JsonDateTime();

        assertNotNull(dateTime);
        assertNull(dateTime.getCustomValue());
    }

    // Method returns string 'datetime' consistently
    @Test
    public void test_returns_datetime_string() {
        JsonDateTime dateTime = new JsonDateTime(LocalDateTime.now());

        String result = dateTime.getCustomTypeName();

        assertEquals("datetime", result);
    }

    // Compare two dates where first date is after second date returns positive number
    @Test
    public void test_compare_returns_positive_when_first_date_after_second() {
        LocalDateTime firstDate = LocalDateTime.of(2023, 12, 25, 10, 30);
        LocalDateTime secondDate = LocalDateTime.of(2023, 12, 24, 10, 30);
        JsonDateTime jsonDateTime = new JsonDateTime(firstDate);

        int result = jsonDateTime.compare(secondDate);

        assertTrue(result > 0);
    }

    // Compare with null LocalDateTime parameter throws NullPointerException
    @Test
    public void test_compare_throws_exception_when_parameter_is_null() {
        LocalDateTime date = LocalDateTime.of(2023, 12, 25, 10, 30);
        JsonDateTime jsonDateTime = new JsonDateTime(date);

        assertThrows(NullPointerException.class, () -> jsonDateTime.compare(null));
    }
}
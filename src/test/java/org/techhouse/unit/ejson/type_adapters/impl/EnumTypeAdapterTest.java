package org.techhouse.unit.ejson.type_adapters.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.EnumTypeAdapter;
import org.techhouse.log.LogWriter;

public class EnumTypeAdapterTest {
    enum TestEnum {
        VALUE1, VALUE2, VALUE3, VALUE_ONE
    }

    enum RenamedInToString {
        ACTIVE {
            @Override
            public String toString() {
                return "active";
            }
        }
    }

    @Test
    public void test_an_overridden_tostring_does_not_change_the_wire_format() {
        EnumTypeAdapter<RenamedInToString> adapter = new EnumTypeAdapter<>(RenamedInToString.class);

        final var serialised = adapter.toJson(RenamedInToString.ACTIVE);
        final var builder = new StringBuilder();
        adapter.toJson(RenamedInToString.ACTIVE, builder);

        assertEquals("\"ACTIVE\"", serialised,
                "serialising toString() while deserialising by name() would round-trip to null the moment"
                        + " any enum overrode toString");
        assertEquals(serialised, builder.toString(), "both toJson overloads must agree");
    }

    @Test
    public void test_an_overridden_tostring_still_round_trips() {
        EnumTypeAdapter<RenamedInToString> adapter = new EnumTypeAdapter<>(RenamedInToString.class);

        final var parsed = adapter.fromJson(new JsonString("ACTIVE"));

        assertEquals(RenamedInToString.ACTIVE, parsed, "fromJson looks the constant up by name()");
    }

    @Test
    public void test_valid_enum_to_json_string() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        String result = adapter.toJson(TestEnum.VALUE1);

        assertEquals("\"VALUE1\"", result);
    }

    @Test
    public void test_null_enum_to_json() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        String result = adapter.toJson(null);

        assertEquals("\"null\"", result);
    }

    @Test
    public void test_constructor_accepts_valid_enum_class() {
        EnumTypeAdapter<TimeUnit> adapter = new EnumTypeAdapter<>(TimeUnit.class);

        JsonBaseElement jsonElement = new JsonString("SECONDS");
        TimeUnit result = adapter.fromJson(jsonElement);

        assertEquals(TimeUnit.SECONDS, result);
    }

    @Test
    public void test_enum_value_converted_to_json_string() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        String result = adapter.toJson(TestEnum.VALUE1);

        assertEquals("\"VALUE1\"", result);
    }

    @Test
    public void test_null_enum_value_handling() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        String result = adapter.toJson(null);

        assertEquals("\"null\"", result);
    }

    @Test
    public void test_valid_enum_string_conversion() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        JsonString jsonString = new JsonString("VALUE_ONE");

        TestEnum result = adapter.fromJson(jsonString);

        assertEquals(TestEnum.VALUE_ONE, result);
    }

    @Test
    public void test_non_string_input_returns_null() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        JsonNumber jsonNumber = new JsonNumber(123);

        TestEnum result = adapter.fromJson(jsonNumber);

        assertNull(result);
    }

    private static String loggedEntryFor(String wireValue) {
        final var entries = new java.util.ArrayList<String>();
        try (MockedStatic<LogWriter> logWriter = mockStatic(LogWriter.class)) {
            logWriter.when(() -> LogWriter.writeLogEntry(anyString()))
                    .thenAnswer(invocation -> entries.add(invocation.getArgument(0)));
            assertNull(new EnumTypeAdapter<>(TestEnum.class).fromJson(new JsonString(wireValue)));
        }
        assertEquals(1, entries.size(), entries.toString());
        return entries.getFirst();
    }

    @Test
    public void unknown_value_logs_one_warning_without_stack_trace_and_returns_null() {
        final var entry = loggedEntryFor("NOT_A_CONSTANT");

        assertTrue(entry.contains("-WARNING-"), entry);
        assertTrue(entry.contains("Unknown enum value for TestEnum: NOT_A_CONSTANT"), entry);
        assertFalse(entry.contains("\n"), entry);
        assertFalse(entry.contains("at org."), entry);
    }

    @Test
    public void forged_newline_value_does_not_create_a_second_log_line() {
        final var entry = loggedEntryFor("X\nFAKE-LOG-LINE\rAGAIN");

        assertFalse(entry.contains("\n"), entry);
        assertFalse(entry.contains("\r"), entry);
        assertTrue(entry.contains("X\\nFAKE-LOG-LINE\\rAGAIN"), entry);
    }

    @Test
    public void overlong_value_is_truncated_in_the_log() {
        final var entry = loggedEntryFor("A".repeat(64) + "TAIL" + "B".repeat(1000));

        assertTrue(entry.endsWith(": " + "A".repeat(64)), entry);
        assertFalse(entry.contains("TAIL"), entry);
    }
}

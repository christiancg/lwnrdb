package org.techhouse.unit.config;

import org.junit.jupiter.api.Test;
import org.techhouse.config.SizeParser;

import static org.junit.jupiter.api.Assertions.*;

public class SizeParserTest {

    @Test
    public void test_parses_plain_bytes() {
        assertEquals(1024L, SizeParser.parse("1024"));
    }

    @Test
    public void test_parses_bytes_with_B_suffix() {
        assertEquals(512L, SizeParser.parse("512B"));
        assertEquals(512L, SizeParser.parse("512b"));
    }

    @Test
    public void test_parses_Kb() {
        assertEquals(1024L, SizeParser.parse("1Kb"));
        assertEquals(2048L, SizeParser.parse("2KB"));
    }

    @Test
    public void test_parses_Mb() {
        assertEquals(1024L * 1024L, SizeParser.parse("1Mb"));
        assertEquals(512L * 1024L * 1024L, SizeParser.parse("512Mb"));
    }

    @Test
    public void test_parses_Gb() {
        assertEquals(1024L * 1024L * 1024L, SizeParser.parse("1Gb"));
        assertEquals(2L * 1024L * 1024L * 1024L, SizeParser.parse("2GB"));
    }

    @Test
    public void test_parses_Tb() {
        assertEquals(1024L * 1024L * 1024L * 1024L, SizeParser.parse("1Tb"));
    }

    @Test
    public void test_case_insensitive() {
        assertEquals(1024L * 1024L, SizeParser.parse("1mB"));
    }

    @Test
    public void test_allows_internal_whitespace() {
        assertEquals(1024L * 1024L, SizeParser.parse("  1 Mb  "));
    }

    @Test
    public void test_parses_zero_as_unlimited() {
        assertEquals(0L, SizeParser.parse("0"));
    }

    @Test
    public void test_parses_minus_one_as_disabled() {
        assertEquals(-1L, SizeParser.parse("-1"));
    }

    @Test
    public void test_rejects_malformed() {
        assertThrows(IllegalArgumentException.class, () -> SizeParser.parse("abc"));
    }

    @Test
    public void test_rejects_negative_other_than_minus_one() {
        assertThrows(IllegalArgumentException.class, () -> SizeParser.parse("-5"));
        assertThrows(IllegalArgumentException.class, () -> SizeParser.parse("-2Mb"));
    }

    @Test
    public void test_rejects_empty() {
        assertThrows(IllegalArgumentException.class, () -> SizeParser.parse(""));
    }

    @Test
    public void test_rejects_null() {
        assertThrows(IllegalArgumentException.class, () -> SizeParser.parse(null));
    }

    @Test
    public void test_rejects_unknown_unit() {
        assertThrows(IllegalArgumentException.class, () -> SizeParser.parse("5Xb"));
    }

}

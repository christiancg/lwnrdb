package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.internal.regex.RegexProgram;

public class RegexTranslatorCacheTest {
    private static RegexProgram programOf(String source) {
        return RegexTranslator.compile(source, "").getProgram();
    }

    @Test
    public void test_a_short_pattern_is_cached_and_reused() {
        final var source = "cache-me-" + System.nanoTime();
        assertSame(programOf(source), programOf(source));
    }

    @Test
    public void test_a_pattern_longer_than_the_per_program_cap_is_never_cached() {
        final var source = "x".repeat(5000) + System.nanoTime();
        assertNotSame(programOf(source), programOf(source));
    }

    @Test
    public void test_the_cache_evicts_once_its_total_source_size_is_passed() {
        final var marker = "evict-me-" + System.nanoTime();
        final var before = programOf(marker);
        assertSame(before, programOf(marker));
        RegexProgram newest = null;
        final var newestSource = "f".repeat(4000) + 299 + "-" + marker;
        for (var i = 0; i < 300; i++) {
            final var program = programOf("f".repeat(4000) + i + "-" + marker);
            if (i == 299) {
                newest = program;
            }
        }
        assertSame(newest, programOf(newestSource));
        assertNotSame(before, programOf(marker));
    }
}

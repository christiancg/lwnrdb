package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.utils.CaseFolding;

public class CaseFoldingTest {
    private static final String EMOJI = "😀";
    private static final String FULLWIDTH_A = "Ａ";
    private static final String LONE_HIGH = "\ud83d";
    private static final String LONE_LOW = "\ude00";
    private static final List<String> FIRST_SET = List.of(EMOJI, FULLWIDTH_A, "aa" + FULLWIDTH_A, LONE_HIGH + EMOJI);
    private static final List<String> SECOND_SET = List.of(EMOJI + "a", LONE_HIGH + EMOJI, "b" + LONE_HIGH + LONE_HIGH,
            FULLWIDTH_A + "a" + EMOJI);

    private static void assertTransitive(List<String> values) {
        for (final var a : values) {
            for (final var b : values) {
                for (final var c : values) {
                    if (CaseFolding.compare(a, b) < 0 && CaseFolding.compare(b, c) < 0) {
                        assertTrue(CaseFolding.compare(a, c) < 0, a + " < " + b + " < " + c);
                    }
                }
            }
        }
    }

    private static void assertAntisymmetric(List<String> values) {
        for (final var a : values) {
            for (final var b : values) {
                assertEquals(Integer.signum(CaseFolding.compare(a, b)), -Integer.signum(CaseFolding.compare(b, a)));
            }
        }
    }

    @Test
    public void test_the_order_is_transitive_around_a_lone_surrogate_before_a_pair() {
        assertTransitive(FIRST_SET);
        assertTransitive(SECOND_SET);
    }

    @Test
    public void test_the_order_is_antisymmetric() {
        assertAntisymmetric(FIRST_SET);
        assertAntisymmetric(SECOND_SET);
    }

    @Test
    public void test_equality_agrees_with_equals_ignore_case_on_well_formed_text() {
        final var pairs = List.of(List.of("Hello", "hELLO"), List.of("café", "CAFÉ"), List.of("σς", "ΣΣ"),
                List.of("ı", "I"), List.of("𐐀", "𐐨"), List.of("abc", "abd"), List.of("straße", "STRASSE"));
        for (final var pair : pairs) {
            assertEquals(pair.get(0).equalsIgnoreCase(pair.get(1)), CaseFolding.equal(pair.get(0), pair.get(1)),
                    pair.toString());
        }
    }

    @Test
    public void test_a_supplementary_cased_letter_matches_its_other_case() {
        assertTrue(CaseFolding.equal("𐐀x", "𐐨X"));
    }

    @Test
    public void test_lone_surrogates_compare_as_themselves() {
        for (final var lone : List.of(LONE_HIGH, LONE_LOW)) {
            assertTrue(CaseFolding.equal(lone + "a", lone + "A"));
            assertTrue(CaseFolding.equal("a" + lone + "b", "A" + lone + "B"));
            assertTrue(CaseFolding.equal("ab" + lone, "AB" + lone));
            assertFalse(CaseFolding.equal(lone, EMOJI));
        }
    }

    @Test
    public void test_a_prefix_sorts_first_and_empty_strings_are_equal() {
        assertTrue(CaseFolding.compare("a", "aB") < 0);
        assertTrue(CaseFolding.compare("AB", "a") > 0);
        assertEquals(0, CaseFolding.compare("", ""));
        assertTrue(CaseFolding.compare("", "a") < 0);
    }

    @Test
    public void test_strings_differing_only_in_case_compare_equal() {
        assertEquals(0, CaseFolding.compare("MiXeD", "mIxEd"));
        assertNotEquals(0, CaseFolding.compare("MiXeD", "mIxEe"));
    }
}

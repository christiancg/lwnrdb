package io.github.christiancg.lwnrdb.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class NamesTest {
    @Test
    public void test_names_follow_the_server_rules() {
        assertEquals("abc", Names.database("abc"));
        assertEquals("a".repeat(64), Names.collection("a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> Names.database("ab"));
        assertThrows(IllegalArgumentException.class, () -> Names.collection("a".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> Names.collection("has space"));
    }

    @Test
    public void test_ids_are_one_to_sixty_four_safe_characters() {
        assertEquals("a", Names.id("a"));
        assertEquals("A-b_9", Names.id("A-b_9"));
        assertThrows(IllegalArgumentException.class, () -> Names.id(""));
        assertThrows(IllegalArgumentException.class, () -> Names.id("a.b"));
    }
}

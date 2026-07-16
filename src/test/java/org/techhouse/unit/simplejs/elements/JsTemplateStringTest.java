package org.techhouse.unit.simplejs.elements;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsTemplateString;

public class JsTemplateStringTest {
    // Quasis and expressions are exposed as provided
    @Test
    public void test_getters() {
        final List<List<JsBaseElement>> expressions = List.of(List.of());
        final var template = new JsTemplateString(List.of("a", "b"), expressions);
        assertEquals(List.of("a", "b"), template.getQuasis());
        assertEquals(expressions, template.getExpressions());
    }

    // The quasis/expressions count invariant is enforced
    @Test
    public void test_invariant_violation_throws() {
        assertThrows(IllegalArgumentException.class, () -> new JsTemplateString(List.of("a", "b"), List.of()));
    }
}

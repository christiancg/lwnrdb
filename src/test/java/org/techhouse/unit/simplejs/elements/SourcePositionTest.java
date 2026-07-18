package org.techhouse.unit.simplejs.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.elements.SourcePosition;

public class SourcePositionTest {
    // Getters expose the offset, length, line and column passed to the constructor
    @Test
    public void test_getters() {
        final var position = new SourcePosition(10, 3, 2, 4);
        assertEquals(10, position.getOffset());
        assertEquals(3, position.getLength());
        assertEquals(2, position.getLine());
        assertEquals(4, position.getColumn());
    }
}

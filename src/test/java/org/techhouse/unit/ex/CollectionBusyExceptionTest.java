package org.techhouse.unit.ex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.CollectionBusyException;

public class CollectionBusyExceptionTest {
    @Test
    public void test_has_no_stack_trace() {
        assertEquals(0, new CollectionBusyException("db|coll", 0L).getStackTrace().length);
    }

    @Test
    public void test_message_names_the_collection_and_the_wait() {
        assertEquals("Collection db|coll stayed locked for 25ms; skipping it rather than waiting on it",
                new CollectionBusyException("db|coll", 25L).getMessage());
    }

    @Test
    public void test_suppression_is_disabled() {
        final var busy = new CollectionBusyException("db|coll", 0L);

        busy.addSuppressed(new IllegalStateException("other"));

        assertEquals(0, busy.getSuppressed().length);
    }
}

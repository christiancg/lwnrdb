package org.techhouse.unit.ex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.CollectionBusyException;
import org.techhouse.ex.IndexLockBusyException;

public class IndexLockBusyExceptionTest {
    @Test
    public void theMessageNamesTheIndexAndTheWait() {
        assertEquals("Index db|coll|f stayed locked for 25ms; skipping it rather than waiting on it",
                new IndexLockBusyException("db|coll|f", 25L).getMessage());
    }

    @Test
    public void itIsDeferredLikeABusyCollectionAndCarriesNoStackTrace() {
        final var busy = new IndexLockBusyException("db|coll|f", 0L);

        assertInstanceOf(CollectionBusyException.class, busy);
        assertEquals(0, busy.getStackTrace().length);
    }
}

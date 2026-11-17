package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

public class FieldIndexStoreEmptyTypeTest {
    @TempDir
    File tmp;

    private File base;
    private File log;

    @BeforeEach
    public void setUp() throws IOException {
        base = new File(tmp, "coll-field-String.idx");
        log = new File(tmp, "coll-field-String.idxlog");
        Files.writeString(base.toPath(), "old\u001Fghost\n");
        Files.writeString(log.toPath(), "+\u001Fother\u001Fghost-2\n");
    }

    private static File refusingDelete(File file) {
        final var spy = Mockito.spy(file);
        Mockito.doReturn(false).when(spy).delete();
        return spy;
    }

    private static File recordingDeletes(File file, List<String> deletes) {
        final var spy = Mockito.spy(file);
        Mockito.doAnswer(call -> {
            deletes.add(file.getName());
            return call.callRealMethod();
        }).when(spy).delete();
        return spy;
    }

    @Test
    public void anEmptiedTypeDeletesItsBaseBeforeItsLog() throws IOException {
        final var deletes = new ArrayList<String>();

        FieldIndexStore.replaceType(recordingDeletes(base, deletes), recordingDeletes(log, deletes), List.of());

        assertEquals(List.of(base.getName(), log.getName()), deletes);
        assertFalse(base.exists());
        assertFalse(log.exists());
    }

    @Test
    public void aBaseThatCannotBeDeletedIsRewrittenEmpty() throws IOException {
        FieldIndexStore.replaceType(refusingDelete(base), log, List.of());

        assertTrue(base.exists());
        assertEquals(0L, base.length(), "an empty base holds no ghost ids");
        assertFalse(log.exists());
    }

    @Test
    public void aBaseThatCannotBeDeletedOrRewrittenFailsAndKeepsTheLog() throws IOException {
        Files.delete(base.toPath());
        assertTrue(base.mkdir());
        Files.writeString(new File(base, "occupant").toPath(), "x");
        final var logSpy = Mockito.spy(log);

        assertThrows(IOException.class, () -> FieldIndexStore.replaceType(refusingDelete(base), logSpy, List.of()));

        assertTrue(log.exists(), "the log is left alone while the base still holds its ids");
        Mockito.verify(logSpy, Mockito.never()).delete();
    }

    @Test
    public void aLogThatCannotBeDeletedFailsTheBatch() {
        final var failure = assertThrows(IOException.class,
                () -> FieldIndexStore.replaceType(base, refusingDelete(log), List.of()));

        assertTrue(failure.getMessage().contains(log.getName()), failure.getMessage());
        assertFalse(base.exists());
    }
}

package org.techhouse.unit.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.techhouse.config.Globals;
import org.techhouse.log.LogWriter;
import org.techhouse.log.Logger;

public class LoggerExceptionFormatTest {
    private static String loggedEntry(Throwable exception) {
        final var captor = ArgumentCaptor.forClass(String.class);
        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            Logger.logFor(LoggerExceptionFormatTest.class).error("save failed", exception);
            logWriterMock.verify(() -> LogWriter.writeLogEntry(captor.capture()));
        }
        return captor.getValue();
    }

    private static Throwable withFrame(Throwable exception, String method) {
        exception.setStackTrace(
                new StackTraceElement[]{new StackTraceElement("org.techhouse.fs.Pages", method, "Pages.java", 7)});
        return exception;
    }

    @Test
    public void the_entry_names_the_exception_class_and_puts_each_frame_on_its_own_line() {
        final var lines = loggedEntry(withFrame(new IllegalStateException("broken"), "write")).split(Globals.NEWLINE);

        assertEquals(2, lines.length);
        assertTrue(lines[0].endsWith("save failed -> java.lang.IllegalStateException: broken"), lines[0]);
        assertEquals("\tat org.techhouse.fs.Pages.write(Pages.java:7)", lines[1]);
    }

    @Test
    public void every_cause_follows_with_its_class_message_and_frames() {
        final var cause = withFrame(new IOException("No space left on device"), "force");
        final var lines = loggedEntry(
                withFrame(new UncheckedIOException("could not append", (IOException) cause), "append"))
                .split(Globals.NEWLINE);

        assertEquals(4, lines.length);
        assertTrue(lines[0].endsWith(" -> java.io.UncheckedIOException: could not append"), lines[0]);
        assertEquals("\tat org.techhouse.fs.Pages.append(Pages.java:7)", lines[1]);
        assertEquals("Caused by: java.io.IOException: No space left on device", lines[2]);
        assertEquals("\tat org.techhouse.fs.Pages.force(Pages.java:7)", lines[3]);
    }

    @Test
    public void an_exception_without_a_message_still_names_its_class() {
        final var entry = loggedEntry(withFrame(new NullPointerException(), "read"));

        assertTrue(entry.contains(" -> java.lang.NullPointerException" + Globals.NEWLINE), entry);
    }

    @Test
    public void a_cause_cycle_is_written_once() {
        final var first = withFrame(new IllegalStateException("first"), "a");
        final var second = withFrame(new IllegalArgumentException("second"), "b");
        first.initCause(second);
        second.initCause(first);

        final var lines = loggedEntry(first).split(Globals.NEWLINE);

        assertEquals(4, lines.length);
        assertEquals("Caused by: java.lang.IllegalArgumentException: second", lines[2]);
    }

    @Test
    public void a_message_with_a_line_break_stays_on_its_line() {
        final var entry = loggedEntry(withFrame(new IllegalStateException("one\ntwo"), "write"));

        assertTrue(entry.contains("java.lang.IllegalStateException: one\\ntwo" + Globals.NEWLINE), entry);
    }
}

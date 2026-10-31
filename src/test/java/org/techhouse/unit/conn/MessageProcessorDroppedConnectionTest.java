package org.techhouse.unit.conn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.log.Logger;
import org.techhouse.test.TestUtils;

public class MessageProcessorDroppedConnectionTest {
    private static final String ADMIN = "dropped_admin";

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createAdmin(ADMIN);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static Logger dropAfterAuthenticating(IOException failure) throws Exception {
        final var authenticate = ("{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN
                + "\",\"password\":\"password123\"}\n").getBytes(StandardCharsets.UTF_8);
        final var in = new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                throw failure;
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public int read(byte[] target, int offset, int length) throws IOException {
                if (served > 0) {
                    throw failure;
                }
                served = authenticate.length;
                System.arraycopy(authenticate, 0, target, offset, authenticate.length);
                return authenticate.length;
            }
        };
        final var processor = new MessageProcessor(TestUtils.clientSocket(in, new ByteArrayOutputStream()));
        final var logger = Mockito.mock(Logger.class);
        TestUtils.setPrivateField(processor, "logger", logger);
        final var thread = Thread.ofPlatform().start(processor);
        thread.join(10_000);
        return logger;
    }

    @Test
    public void aPeerResetIsLoggedAtInfoWithoutAStackTrace() throws Exception {
        final var logger = dropAfterAuthenticating(new SocketException("Connection reset"));

        verify(logger).info(argThat((String line) -> line.contains("dropped: SocketException: Connection reset")));
        verify(logger, never()).error(anyString(), any(Throwable.class));
    }

    @Test
    public void anEndOfStreamMidLineIsLoggedAtInfo() throws Exception {
        final var logger = dropAfterAuthenticating(new EOFException("truncated"));

        verify(logger).info(argThat((String line) -> line.contains("dropped: EOFException: truncated")));
        verify(logger, never()).error(anyString(), any(Throwable.class));
    }

    @Test
    public void anyOtherIoFailureKeepsItsErrorAndStackTrace() throws Exception {
        final var failure = new IOException("disk on fire");
        final var logger = dropAfterAuthenticating(failure);

        verify(logger).error("General error in MessageProcessor", failure);
    }
}

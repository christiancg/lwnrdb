package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.SocketException;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.SocketServer;
import org.techhouse.test.TestUtils;

public class SocketServerAcceptFailureTest {
    private static Method acceptOne() throws Exception {
        final var method = SocketServer.class.getDeclaredMethod("acceptOne", ServerSocket.class);
        method.setAccessible(true);
        return method;
    }

    @Test
    public void test_an_accept_failure_is_survived_with_a_short_back_off() throws Exception {
        final var server = new SocketServer(0);
        final var listening = mock(ServerSocket.class);
        when(listening.accept()).thenThrow(new SocketException("Too many open files"));
        final var started = System.nanoTime();

        assertDoesNotThrow(() -> acceptOne().invoke(server, listening));
        assertDoesNotThrow(() -> acceptOne().invoke(server, listening));

        assertTrue(System.nanoTime() - started >= 150_000_000L, "accept failures must back off");
        verify(listening, times(2)).accept();
    }

    @Test
    public void test_an_accept_failure_while_stopping_returns_at_once() throws Exception {
        final var server = new SocketServer(0);
        TestUtils.setPrivateField(server, "stopping", true);
        try (var listening = mock(ServerSocket.class)) {
            when(listening.accept()).thenThrow(new SocketException("Socket closed"));
            final var started = System.nanoTime();

            assertDoesNotThrow(() -> acceptOne().invoke(server, listening));

            assertTrue(System.nanoTime() - started < 90_000_000L, "a stopping server must not back off");
        }
    }
}

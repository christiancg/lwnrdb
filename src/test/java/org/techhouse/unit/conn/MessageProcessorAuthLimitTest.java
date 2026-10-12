package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.auth.AuthFailureThrottle;
import org.techhouse.test.TestUtils;

public class MessageProcessorAuthLimitTest {
    private static final String USERNAME = "limited_user";
    private static final String PASSWORD = "password123";
    private final AuthFailureThrottle throttle = IocContainer.get(AuthFailureThrottle.class);
    private final PipedOutputStream toServer = new PipedOutputStream();
    private final PipedInputStream fromServer = new PipedInputStream(1 << 16);
    private BufferedReader responses;
    private Thread connection;
    private long originalBase;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createAdmin(USERNAME);
        originalBase = TestUtils.getPrivateField(throttle, "baseMillis", Long.class);
        TestUtils.setPrivateField(throttle, "baseMillis", 0L);
        final var socket = TestUtils.clientSocket(new PipedInputStream(toServer, 1 << 16),
                new PipedOutputStream(fromServer));
        responses = new BufferedReader(new InputStreamReader(fromServer, StandardCharsets.UTF_8));
        connection = new Thread(new MessageProcessor(socket));
        connection.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        toServer.close();
        connection.join(10_000);
        TestUtils.setPrivateField(throttle, "baseMillis", originalBase);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private String send(String message) throws Exception {
        toServer.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
        return responses.readLine();
    }

    private static String authenticate(String password) {
        return "{\"type\":\"AUTHENTICATE\",\"username\":\"" + USERNAME + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    public void list_databases_before_authentication_answers_401_1() throws Exception {
        final var answer = send("{\"type\":\"LIST_DATABASES\"}");

        assertTrue(answer.contains("401-1"), answer);
        assertTrue(answer.contains("UNAUTHENTICATED"), answer);
    }

    @Test
    public void connection_is_closed_after_the_response_carrying_401_4() throws Exception {
        for (var i = 0; i < 4; i++) {
            final var answer = send(authenticate("wrong-password"));
            assertTrue(answer.contains("401-3"), answer);
        }

        final var last = send(authenticate("wrong-password"));

        assertTrue(last.contains("401-4"), last);
        assertTrue(last.contains("UNAUTHENTICATED"), last);
        connection.join(10_000);
        assertFalse(connection.isAlive(), "the server must close the connection after 401-4");
    }

    @Test
    public void authenticate_and_close_connection_remain_public() throws Exception {
        final var authenticated = send(authenticate(PASSWORD));
        assertTrue(authenticated.contains("\"OK\""), authenticated);

        final var listed = send("{\"type\":\"LIST_DATABASES\"}");
        assertTrue(listed.contains("\"OK\""), listed);

        final var closed = send("{\"type\":\"CLOSE_CONNECTION\"}");
        assertTrue(closed.contains("CLOSE_CONNECTION"), closed);
        connection.join(10_000);
        assertFalse(connection.isAlive());
    }
}

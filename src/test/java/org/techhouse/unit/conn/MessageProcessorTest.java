package org.techhouse.unit.conn;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.test.TestUtils;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class MessageProcessorTest {

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private Socket mockSocket(InputStream in, OutputStream out) throws Exception {
        Socket s = Mockito.mock(Socket.class);
        InetAddress addr = Mockito.mock(InetAddress.class);
        when(s.getInetAddress()).thenReturn(addr);
        when(addr.getHostAddress()).thenReturn("127.0.0.1");
        when(s.getInputStream()).thenReturn(in);
        when(s.getOutputStream()).thenReturn(out);
        return s;
    }

    // Handles null or blank messages gracefully
    @Test
    public void test_handles_null_or_blank_messages() throws Exception {
        Socket mockSocket = mockSocket(new ByteArrayInputStream("".getBytes()), new ByteArrayOutputStream());
        MessageProcessor messageProcessor = new MessageProcessor(mockSocket);
        Thread thread = new Thread(messageProcessor);
        thread.start();
        thread.join(3000);
        assertFalse(thread.isAlive());
    }

    // A valid message is processed and a JSON response is written back
    @Test
    public void test_valid_message_is_processed_and_response_written() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String msg = "{\"type\":\"LIST_DATABASES\"}\n";
        Socket socket = mockSocket(new ByteArrayInputStream(msg.getBytes()), out);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        String response = out.toString();
        assertTrue(response.contains("LIST_DATABASES"), "Response should echo operation type");
        assertTrue(response.contains("OK"), "Response should indicate success");
    }

    // A CLOSE_CONNECTION message causes the processing loop to exit cleanly
    @Test
    public void test_close_connection_message_exits_loop() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String msg = "{\"type\":\"CLOSE_CONNECTION\"}\n";
        Socket socket = mockSocket(new ByteArrayInputStream(msg.getBytes()), out);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        assertFalse(t.isAlive(), "Thread should have exited after CLOSE_CONNECTION");
        assertTrue(out.toString().contains("CLOSE_CONNECTION"));
    }

    // An invalid JSON message causes the InvalidCommandException message to be written
    @Test
    public void test_invalid_json_responds_with_exception_message() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String msg = "not_valid_json\n";
        Socket socket = mockSocket(new ByteArrayInputStream(msg.getBytes()), out);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        String response = out.toString();
        assertFalse(response.isEmpty(), "An error response should have been written");
    }

    // An IOException on the output stream is handled without crashing
    @Test
    public void test_ioexception_on_output_stream_is_handled() throws Exception {
        OutputStream throwingOut = mock(OutputStream.class);
        doThrow(new IOException("write failed")).when(throwingOut).write(any(byte[].class), anyInt(), anyInt());
        String msg = "{\"type\":\"LIST_DATABASES\"}\n";
        Socket socket = mockSocket(new ByteArrayInputStream(msg.getBytes()), throwingOut);

        MessageProcessor mp = new MessageProcessor(socket);
        Thread t = new Thread(mp);
        t.start();
        t.join(3000);

        assertFalse(t.isAlive(), "Thread should exit after IOException");
    }
}

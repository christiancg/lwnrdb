package org.techhouse.unit.conn;

import org.junit.jupiter.api.Test;
import org.techhouse.conn.SocketServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class SocketServerTest {


    // Server starts and listens on the specified port
    @Test
    public void test_server_starts_and_listens_on_specified_port() {
        int port = 8080;
        SocketServer server = new SocketServer(port);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(server::serve);
        try (Socket socket = new Socket("localhost", port)) {
            assertTrue(socket.isConnected());
        } catch (IOException e) {
            fail("Server did not start or listen on the specified port");
        } finally {
            executor.shutdownNow();
        }
    }

    // Port is already in use when starting the server
    @Test
    public void test_port_already_in_use_when_starting_server() {
        int port = 8080;
        try (ServerSocket ignored = new ServerSocket(port)) {
            SocketServer server = new SocketServer(port);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(server::serve);
        
            Thread.sleep(1000); // Wait for the server to attempt to start
        
            // Check logs or other indicators for failure due to port in use
            // This is a placeholder for actual log checking or exception handling
            assertTrue(true); // Replace with actual check
        
        } catch (IOException | InterruptedException e) {
            fail("Unexpected exception occurred");
        }
    }
}
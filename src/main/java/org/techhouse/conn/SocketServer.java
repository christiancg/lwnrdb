package org.techhouse.conn;

import org.techhouse.config.Configuration;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocketServer {
    private final int port;
    private final ExecutorService pool;

    public SocketServer(int port) {
        this.port = port;
        this.pool = Executors.newFixedThreadPool(Configuration.getInstance().getMaxConnections());
    }

    public void serve() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server is listening on port " + port);
            while(true) {
                Socket socket = serverSocket.accept();
                pool.execute(new MessageProcessor(socket));
            }
        } catch (IOException ex) {
            System.out.println("I/O error: " + ex.getMessage());
        }
    }
}

package org.techhouse.conn;

import org.techhouse.log.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocketServer {
    private final Logger logger = Logger.logFor(SocketServer.class);
    private final int port;
    private final ExecutorService pool;

    public SocketServer(int port) {
        this.port = port;
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void serve() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            logger.info("Server is listening on port " + port);
            while(true) {
                Socket socket = serverSocket.accept();
                pool.execute(new MessageProcessor(socket));
            }
        } catch (IOException ex) {
            logger.fatal("I/O error while starting server on port " + port, ex);
        }
    }
}

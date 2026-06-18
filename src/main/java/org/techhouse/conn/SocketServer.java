package org.techhouse.conn;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import org.techhouse.log.Logger;

public class SocketServer {
    private final Logger logger = Logger.logFor(SocketServer.class);
    private final int port;
    private final ExecutorService pool;
    private final SSLServerSocketFactory sslServerSocketFactory;

    public SocketServer(int port) {
        this(port, null);
    }

    /**
     * @param sslServerSocketFactory when non-null the server listens over TLS and plaintext clients are
     *                               rejected at the handshake; when null the server listens in plaintext.
     */
    public SocketServer(int port, SSLServerSocketFactory sslServerSocketFactory) {
        this.port = port;
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
        this.sslServerSocketFactory = sslServerSocketFactory;
    }

    public void serve() {
        try (ServerSocket serverSocket = createServerSocket()) {
            logger.info("Server is listening on port " + port + (sslServerSocketFactory != null ? " (TLS)" : ""));
            // With an SSLServerSocket the handshake happens lazily on first read inside the per-connection
            // MessageProcessor, so a plaintext client is rejected there (see MessageProcessor) rather than here.
            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = serverSocket.accept();
                pool.execute(new MessageProcessor(socket));
            }
        } catch (IOException ex) {
            logger.fatal("I/O error while starting server on port " + port, ex);
        }
    }

    private ServerSocket createServerSocket() throws IOException {
        if (sslServerSocketFactory != null) {
            final var sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(port);
            sslServerSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            return sslServerSocket;
        }
        return new ServerSocket(port);
    }
}

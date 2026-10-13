package org.techhouse.conn;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import org.techhouse.config.Globals;
import org.techhouse.conn.tls.TlsContextFactory;
import org.techhouse.log.LogRateLimiter;
import org.techhouse.log.Logger;

public class SocketServer {
    private static final long ACCEPT_FAILURE_PAUSE_MS = 100L;
    private static final int DEFAULT_BACKLOG = 50;
    private static final LogRateLimiter ACCEPT_FAILURE_LOG = new LogRateLimiter(Globals.LOG_RATE_LIMIT_PER_SECOND);
    private final Logger logger = Logger.logFor(SocketServer.class);
    private final int port;
    private final String bindAddress;
    private final ExecutorService pool;
    private final SSLServerSocketFactory sslServerSocketFactory;
    private volatile ServerSocket serverSocket;
    private volatile boolean stopping;

    public SocketServer(int port) {
        this(port, null);
    }

    public SocketServer(int port, SSLServerSocketFactory sslServerSocketFactory) {
        this(port, "", sslServerSocketFactory);
    }

    public SocketServer(int port, String bindAddress, SSLServerSocketFactory sslServerSocketFactory) {
        this.port = port;
        this.bindAddress = bindAddress;
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
        this.sslServerSocketFactory = sslServerSocketFactory;
    }

    public void serve() {
        try (ServerSocket socketForServing = createServerSocket()) {
            serverSocket = socketForServing;
            logger.info("Server is listening on port " + port + (sslServerSocketFactory != null ? " (TLS)" : ""));
            while (!stopping && !socketForServing.isClosed() && !Thread.currentThread().isInterrupted()) {
                acceptOne(socketForServing);
            }
            logger.info("Stopped accepting new connections on port " + port);
        } catch (IOException ex) {
            logger.fatal("I/O error while starting server on port " + port, ex);
        }
    }

    private void acceptOne(ServerSocket socketForServing) {
        final Socket socket;
        try {
            socket = socketForServing.accept();
        } catch (IOException ex) {
            if (!stopping) {
                final var entry = ACCEPT_FAILURE_LOG
                        .admit("Could not accept a connection on port " + port + ": " + ex.getMessage());
                if (entry != null) {
                    logger.warning(entry);
                }
                pauseAfterAcceptFailure();
            }
            return;
        }
        configureAccepted(socket);
        pool.execute(new MessageProcessor(socket, this::isStopping));
    }

    private static void pauseAfterAcceptFailure() {
        try {
            Thread.sleep(ACCEPT_FAILURE_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isStopping() {
        return stopping;
    }

    public void stopAccepting() {
        stopping = true;
        final var socket = serverSocket;
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                logger.warning("Failed to close the listening socket: " + e.getMessage());
            }
        }
    }

    private void configureAccepted(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            SocketClosing.boundTlsClose(socket);
        } catch (IOException e) {
            logger.warning("Could not configure an accepted connection: " + e.getMessage());
        }
    }

    private ServerSocket createServerSocket() throws IOException {
        final var address = bindAddress == null || bindAddress.isBlank() ? null : InetAddress.getByName(bindAddress);
        if (sslServerSocketFactory != null) {
            final var sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(port,
                    DEFAULT_BACKLOG, address);
            sslServerSocket.setEnabledProtocols(TlsContextFactory.enabledProtocols());
            return sslServerSocket;
        }
        return new ServerSocket(port, DEFAULT_BACKLOG, address);
    }
}

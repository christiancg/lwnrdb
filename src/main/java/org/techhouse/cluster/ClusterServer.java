package org.techhouse.cluster;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import org.techhouse.log.Logger;

public class ClusterServer {
    private static final int BACKLOG = 50;
    private final Logger logger = Logger.logFor(ClusterServer.class);
    private final int port;
    private final String bindAddress;
    private final SSLServerSocketFactory sslServerSocketFactory;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public ClusterServer(int port, String bindAddress, SSLServerSocketFactory sslServerSocketFactory) {
        this.port = port;
        this.bindAddress = bindAddress;
        this.sslServerSocketFactory = sslServerSocketFactory;
    }

    public void start() throws IOException {
        serverSocket = createServerSocket();
        running = true;
        final var thread = new Thread(this::serve, "cluster-server");
        thread.setDaemon(true);
        thread.start();
        logger.info("Cluster server listening on " + bindAddress + ":" + port
                + (sslServerSocketFactory != null ? " (TLS)" : ""));
    }

    private void serve() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                final var socket = serverSocket.accept();
                pool.execute(new ClusterConnectionHandler(socket));
            } catch (IOException e) {
                if (running) {
                    logger.warning("Cluster server accept failed: " + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.warning("Error closing cluster server: " + e.getMessage());
        }
        pool.shutdownNow();
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    private ServerSocket createServerSocket() throws IOException {
        final var address = InetAddress.getByName(bindAddress);
        if (sslServerSocketFactory != null) {
            final var sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(port, BACKLOG,
                    address);
            sslServerSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            return sslServerSocket;
        }
        return new ServerSocket(port, BACKLOG, address);
    }
}

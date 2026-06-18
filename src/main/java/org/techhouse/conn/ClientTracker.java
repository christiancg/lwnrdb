package org.techhouse.conn;

import java.net.Socket;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Configuration;
import org.techhouse.data.Client;

public class ClientTracker {
    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();
    private final Configuration configuration = Configuration.getInstance();

    public UUID addClient(Socket socket) {
        final var maxConnections = configuration.getMaxConnections();
        if (maxConnections == 0 || maxConnections > clients.size()) {
            final var clientId = UUID.randomUUID();
            clients.put(clientId, new Client(socket.getInetAddress().getHostAddress()));
            return clientId;
        }
        return null;
    }

    public void removeById(UUID clientId) {
        clients.remove(clientId);
    }

    public void updateLastCommandTime(UUID clientId) {
        if (clientId == null)
            return;
        final var client = clients.get(clientId);
        if (client != null) {
            client.setLastCommandTime(LocalDateTime.now());
        }
    }

    public void setAuthenticatedUser(UUID clientId, String username) {
        if (clientId == null)
            return;
        final var client = clients.get(clientId);
        if (client != null) {
            client.setAuthenticatedUsername(username);
        }
    }

    public String getAuthenticatedUsername(UUID clientId) {
        if (clientId == null)
            return null;
        final var client = clients.get(clientId);
        return client != null ? client.getAuthenticatedUsername() : null;
    }
}

package org.techhouse.conn;

import org.techhouse.config.Configuration;
import org.techhouse.data.Client;

import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ClientTracker {
    private final Map<UUID, Client> clients = new HashMap<>();

    public UUID addClient(Socket socket) {
        if (Configuration.getInstance().getMaxConnections() > clients.size()) {
            final var clientId = UUID.randomUUID();
            clients.put(clientId, new Client(socket.getInetAddress().getHostAddress()));
            return clientId;
        }
        return null;
    }

    public void removeById(UUID clientId) {
        clients.remove(clientId);
    }
}

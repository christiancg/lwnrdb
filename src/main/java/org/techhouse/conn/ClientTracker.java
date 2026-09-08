package org.techhouse.conn;

import java.io.BufferedWriter;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.config.Configuration;
import org.techhouse.data.Client;
import org.techhouse.data.Transaction;

public class ClientTracker {
    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();
    // Owner-side registry for forwarded transactions: edge session id -> the session running its buffered
    // operations on a dedicated single-thread executor (see TxSession).
    private final Map<String, TxSession> txSessions = new ConcurrentHashMap<>();
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

    // Registers a transient, socket-less authenticated client so a forwarded/replicated admin operation can
    // execute on this node as the original acting user (bypasses the maxConnections limit). The caller must
    // removeById it when the operation completes.
    public UUID registerForwardedClient(String username) {
        final var clientId = UUID.randomUUID();
        final var client = new Client("forwarded");
        client.setAuthenticatedUsername(username);
        clients.put(clientId, client);
        return clientId;
    }

    // Resolves (creating on first use) the persistent synthetic client that runs a forwarded transaction's
    // buffered operations on the owner. Unlike registerForwardedClient this client is retained across the
    // session's forwarded messages and is removed only by removeTxSession (commit/rollback/reaper).
    public TxSession registerTxSession(String sessionId, String username, String edgeNodeId) {
        return txSessions.computeIfAbsent(sessionId, _ -> {
            final var id = UUID.randomUUID();
            final var client = new Client("tx-forwarded");
            client.setAuthenticatedUsername(username);
            client.setLastCommandTime(LocalDateTime.now());
            clients.put(id, client);
            final var executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("tx-session-", 0).factory());
            return new TxSession(id, executor, edgeNodeId);
        });
    }

    public void removeTxSession(String sessionId) {
        final var session = txSessions.remove(sessionId);
        if (session != null) {
            clients.remove(session.clientId());
            session.shutdown();
        }
    }

    public TxSession txSession(String sessionId) {
        return sessionId != null ? txSessions.get(sessionId) : null;
    }

    public Map<String, TxSession> txSessionsSnapshot() {
        return new HashMap<>(txSessions);
    }

    public void markLocalSlice(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        if (client != null) {
            client.markLocalSlice();
        }
    }

    public boolean hasLocalSlice(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        return client != null && client.hasLocalSlice();
    }

    public void addTransactionParticipant(UUID clientId, String ownerAddress) {
        final var client = clientId != null ? clients.get(clientId) : null;
        if (client != null) {
            client.addTransactionParticipant(ownerAddress);
        }
    }

    public Set<String> transactionParticipants(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        return client != null ? client.getTransactionParticipants() : Set.of();
    }

    public void clearTransactionState(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        if (client != null) {
            client.clearTransactionState();
        }
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

    public void registerWriter(UUID clientId, BufferedWriter writer) {
        if (clientId == null)
            return;
        final var client = clients.get(clientId);
        if (client != null) {
            client.setWriter(writer);
        }
    }

    public BufferedWriter getWriter(UUID clientId) {
        if (clientId == null)
            return null;
        final var client = clients.get(clientId);
        return client != null ? client.getWriter() : null;
    }

    public ReentrantLock getWriterLock(UUID clientId) {
        if (clientId == null)
            return null;
        final var client = clients.get(clientId);
        return client != null ? client.getWriterLock() : null;
    }

    public java.util.Set<UUID> clientIdsSnapshot() {
        return java.util.Set.copyOf(clients.keySet());
    }

    public Transaction getActiveTransaction(UUID clientId) {
        if (clientId == null)
            return null;
        final var client = clients.get(clientId);
        return client != null ? client.getActiveTransaction() : null;
    }

    public void setActiveTransaction(UUID clientId, Transaction transaction) {
        if (clientId == null)
            return;
        final var client = clients.get(clientId);
        if (client != null) {
            client.setActiveTransaction(transaction);
        }
    }

    public void clearActiveTransaction(UUID clientId) {
        setActiveTransaction(clientId, null);
    }
}

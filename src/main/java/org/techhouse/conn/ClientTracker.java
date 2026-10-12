package org.techhouse.conn;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.config.Configuration;
import org.techhouse.data.Client;
import org.techhouse.data.Transaction;

public class ClientTracker {
    private static final String UNKNOWN_ADDRESS = "unknown";
    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();
    private final Map<UUID, Runnable> disconnectSignals = new ConcurrentHashMap<>();
    private final Map<UUID, CountDownLatch> departures = new ConcurrentHashMap<>();
    private final Map<String, TxSession> txSessions = new ConcurrentHashMap<>();
    private final AtomicInteger socketClients = new AtomicInteger();
    private final Configuration configuration = Configuration.getInstance();

    public UUID addClient(Socket socket) {
        final var maxConnections = configuration.getMaxConnections();
        if (socketClients.incrementAndGet() > maxConnections && maxConnections != 0) {
            socketClients.decrementAndGet();
            return null;
        }
        final var clientId = UUID.randomUUID();
        clients.put(clientId, new Client(socket.getInetAddress().getHostAddress()));
        disconnectSignals.put(clientId, () -> closeQuietly(socket));
        return clientId;
    }

    public void removeById(UUID clientId) {
        clients.remove(clientId);
        if (disconnectSignals.remove(clientId) != null) {
            socketClients.decrementAndGet();
        }
        final var departure = departures.remove(clientId);
        if (departure != null) {
            departure.countDown();
        }
    }

    public CountDownLatch signalDisconnect(UUID clientId) {
        final var departure = new CountDownLatch(1);
        departures.put(clientId, departure);
        final var signal = disconnectSignals.get(clientId);
        if (signal == null) {
            departures.remove(clientId);
            return null;
        }
        signal.run();
        return departure;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // The caller must removeById this transient client when the operation completes, or it leaks.
    public UUID registerForwardedClient(String username) {
        final var clientId = UUID.randomUUID();
        final var client = new Client("forwarded");
        client.setAuthenticatedUsername(username);
        clients.put(clientId, client);
        return clientId;
    }

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

    public void markTransactionReaped(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        if (client != null) {
            client.markTransactionReaped();
        }
    }

    public boolean consumeTransactionReaped(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        return client != null && client.consumeTransactionReaped();
    }

    public void markRemoteSliceFenced(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        if (client != null) {
            client.markRemoteSliceFenced();
        }
    }

    public boolean hasFencedRemoteSlice(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        return client != null && client.hasFencedRemoteSlice();
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

    public void recordTransactionWrite(UUID clientId, String collectionId, String holder) {
        final var client = clientId != null ? clients.get(clientId) : null;
        if (client != null) {
            client.recordTransactionWrite(collectionId, holder);
        }
    }

    public Set<String> transactionWriteHolders(UUID clientId, Collection<String> collectionIds) {
        final var client = clientId != null ? clients.get(clientId) : null;
        return client != null ? client.transactionWriteHolders(collectionIds) : Set.of();
    }

    public void clearTransactionState(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        if (client != null) {
            client.clearTransactionState();
        }
    }

    public long millisSinceLastCommand(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        final var last = client != null ? client.getLastCommandTime() : null;
        return last == null ? 0 : Duration.between(last, LocalDateTime.now()).toMillis();
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

    public void deauthenticateUser(String username) {
        deauthenticateUserExcept(username, null);
    }

    public void deauthenticateUserExcept(String username, UUID keepClientId) {
        for (final var entry : clients.entrySet()) {
            if (!entry.getKey().equals(keepClientId) && disconnectSignals.containsKey(entry.getKey())
                    && username.equals(entry.getValue().getAuthenticatedUsername())) {
                entry.getValue().setAuthenticatedUsername(null);
            }
        }
    }

    public int recordFailedAuthentication(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        return client != null ? client.incrementFailedAuthentications() : 0;
    }

    public String addressOf(UUID clientId) {
        final var client = clientId != null ? clients.get(clientId) : null;
        return client != null ? client.getAddress() : UNKNOWN_ADDRESS;
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

    public boolean hasActiveTransaction(String transactionId) {
        if (transactionId == null) {
            return false;
        }
        for (final var client : clients.values()) {
            final var transaction = client.getActiveTransaction();
            if (transaction != null && transaction.getTransactionId().toString().equals(transactionId)) {
                return true;
            }
        }
        return false;
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

package org.techhouse.data;

import java.io.BufferedWriter;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class Client {
    private final String address;
    private final LocalDateTime connectionTime = LocalDateTime.now();
    private LocalDateTime lastCommandTime;
    private String authenticatedUsername;
    private volatile BufferedWriter writer;
    private final ReentrantLock writerLock = new ReentrantLock();
    private volatile Transaction activeTransaction;
    private volatile boolean hasLocalSlice;
    private volatile boolean remoteSliceFenced;
    private final AtomicBoolean transactionReaped = new AtomicBoolean();
    private final Set<String> transactionParticipants = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> transactionWriteHolders = new ConcurrentHashMap<>();
    private final AtomicInteger failedAuthentications = new AtomicInteger();

    public Client(String address) {
        this.address = address;
    }

    public String getAddress() {
        return address;
    }

    public int incrementFailedAuthentications() {
        return failedAuthentications.incrementAndGet();
    }

    public LocalDateTime getConnectionTime() {
        return connectionTime;
    }

    public LocalDateTime getLastCommandTime() {
        return lastCommandTime;
    }

    public void setLastCommandTime(LocalDateTime lastCommandTime) {
        this.lastCommandTime = lastCommandTime;
    }

    public String getAuthenticatedUsername() {
        return authenticatedUsername;
    }

    public void setAuthenticatedUsername(String authenticatedUsername) {
        this.authenticatedUsername = authenticatedUsername;
    }

    public BufferedWriter getWriter() {
        return writer;
    }

    public void setWriter(BufferedWriter writer) {
        this.writer = writer;
    }

    public ReentrantLock getWriterLock() {
        return writerLock;
    }

    public Transaction getActiveTransaction() {
        return activeTransaction;
    }

    public void setActiveTransaction(Transaction activeTransaction) {
        this.activeTransaction = activeTransaction;
    }

    public boolean hasLocalSlice() {
        return hasLocalSlice;
    }

    public void markLocalSlice() {
        this.hasLocalSlice = true;
    }

    public boolean hasFencedRemoteSlice() {
        return remoteSliceFenced;
    }

    public void markRemoteSliceFenced() {
        this.remoteSliceFenced = true;
    }

    public void markTransactionReaped() {
        transactionReaped.set(true);
    }

    public boolean consumeTransactionReaped() {
        return transactionReaped.getAndSet(false);
    }

    public Set<String> getTransactionParticipants() {
        return transactionParticipants;
    }

    public void addTransactionParticipant(String ownerAddress) {
        transactionParticipants.add(ownerAddress);
    }

    public void recordTransactionWrite(String collectionId, String holder) {
        transactionWriteHolders.computeIfAbsent(collectionId, _ -> ConcurrentHashMap.newKeySet()).add(holder);
    }

    public Set<String> transactionWriteHolders(Collection<String> collectionIds) {
        final var holders = new HashSet<String>();
        for (final var collectionId : collectionIds) {
            holders.addAll(transactionWriteHolders.getOrDefault(collectionId, Set.of()));
        }
        return holders;
    }

    public void clearTransactionState() {
        hasLocalSlice = false;
        remoteSliceFenced = false;
        transactionParticipants.clear();
        transactionWriteHolders.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Client client))
            return false;
        return Objects.equals(address, client.address) && Objects.equals(connectionTime, client.connectionTime)
                && Objects.equals(lastCommandTime, client.lastCommandTime)
                && Objects.equals(authenticatedUsername, client.authenticatedUsername);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, connectionTime, lastCommandTime, authenticatedUsername);
    }

    @Override
    public String toString() {
        return "Client(address=" + address + ", connectionTime=" + connectionTime + ", lastCommandTime="
                + lastCommandTime + ", authenticatedUsername=" + authenticatedUsername + ")";
    }
}

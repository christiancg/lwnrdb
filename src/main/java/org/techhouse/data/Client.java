package org.techhouse.data;

import java.io.BufferedWriter;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public class Client {
    private final String address;
    private final LocalDateTime connectionTime = LocalDateTime.now();
    private LocalDateTime lastCommandTime;
    private String authenticatedUsername;
    private volatile BufferedWriter writer;
    private final ReentrantLock writerLock = new ReentrantLock();

    public Client(String address) {
        this.address = address;
    }

    public String getAddress() {
        return address;
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

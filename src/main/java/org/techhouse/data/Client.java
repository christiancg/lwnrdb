package org.techhouse.data;

import java.time.LocalDateTime;
import java.util.Objects;

public class Client {
    private final String address;
    private final LocalDateTime connectionTime = LocalDateTime.now();
    private LocalDateTime lastCommandTime;

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Client client)) return false;
        return Objects.equals(address, client.address) && Objects.equals(connectionTime, client.connectionTime) && Objects.equals(lastCommandTime, client.lastCommandTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, connectionTime, lastCommandTime);
    }

    @Override
    public String toString() {
        return "Client(address=" + address + ", connectionTime=" + connectionTime + ", lastCommandTime=" + lastCommandTime + ")";
    }
}

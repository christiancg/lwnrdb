package io.github.christiancg.lwnrdb.client.error;

import io.github.christiancg.lwnrdb.client.protocol.Status;

public class ServerClosedConnectionException extends LwnrdbException {
    public ServerClosedConnectionException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}

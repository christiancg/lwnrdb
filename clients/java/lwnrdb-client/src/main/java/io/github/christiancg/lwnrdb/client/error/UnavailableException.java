package io.github.christiancg.lwnrdb.client.error;

import io.github.christiancg.lwnrdb.client.protocol.Status;

public class UnavailableException extends LwnrdbException {
    public UnavailableException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}

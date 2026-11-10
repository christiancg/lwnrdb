package io.github.christiancg.lwnrdb.client.error;

import io.github.christiancg.lwnrdb.client.protocol.Status;

public class ConflictException extends LwnrdbException {
    public ConflictException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}

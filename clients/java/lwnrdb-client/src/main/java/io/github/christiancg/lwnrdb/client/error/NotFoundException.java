package io.github.christiancg.lwnrdb.client.error;

import io.github.christiancg.lwnrdb.client.protocol.Status;

public class NotFoundException extends LwnrdbException {
    public NotFoundException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}

package io.github.christiancg.lwnrdb.client.error;

import io.github.christiancg.lwnrdb.client.protocol.Status;

public class ForbiddenException extends LwnrdbException {
    public ForbiddenException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}

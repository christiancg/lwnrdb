package io.github.christiancg.lwnrdb.client.error;

import io.github.christiancg.lwnrdb.client.protocol.Status;

public class UnknownOutcomeException extends LwnrdbException {
    public UnknownOutcomeException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}

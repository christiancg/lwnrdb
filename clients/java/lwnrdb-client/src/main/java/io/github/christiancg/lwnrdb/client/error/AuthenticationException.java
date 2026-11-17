package io.github.christiancg.lwnrdb.client.error;

import io.github.christiancg.lwnrdb.client.protocol.Status;

public class AuthenticationException extends LwnrdbException {
    public AuthenticationException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}

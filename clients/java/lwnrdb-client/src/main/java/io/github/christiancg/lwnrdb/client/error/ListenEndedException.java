package io.github.christiancg.lwnrdb.client.error;

import io.github.christiancg.lwnrdb.client.protocol.Status;

public class ListenEndedException extends LwnrdbException {
    public ListenEndedException(String message, String rawCode, Status status) {
        super(message, rawCode, status);
    }
}

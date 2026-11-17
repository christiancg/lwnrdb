package io.github.christiancg.lwnrdb.client.error;

import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import io.github.christiancg.lwnrdb.client.protocol.Status;
import java.util.Optional;

public class LwnrdbException extends RuntimeException {
    private final String rawCode;
    private final Status status;

    public LwnrdbException(String message, String rawCode, Status status) {
        super(message);
        this.rawCode = rawCode;
        this.status = status;
    }

    public LwnrdbException(String message, Throwable cause) {
        super(message, cause);
        this.rawCode = null;
        this.status = null;
    }

    public Optional<String> rawCode() {
        return Optional.ofNullable(rawCode);
    }

    public Optional<ErrorCode> errorCode() {
        return ErrorCode.fromCode(rawCode);
    }

    public Optional<Status> status() {
        return Optional.ofNullable(status);
    }

    public boolean hasCode(ErrorCode code) {
        return code.code().equals(rawCode);
    }
}

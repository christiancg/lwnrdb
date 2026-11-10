package io.github.christiancg.lwnrdb.client.error;

public class ConnectionLostException extends LwnrdbException {
    public ConnectionLostException(String message, Throwable cause) {
        super(message, cause);
    }
}

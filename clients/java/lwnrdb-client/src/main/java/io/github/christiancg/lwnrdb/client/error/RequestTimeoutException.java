package io.github.christiancg.lwnrdb.client.error;

public class RequestTimeoutException extends ConnectionLostException {
    public RequestTimeoutException(String message) {
        super(message, null);
    }
}

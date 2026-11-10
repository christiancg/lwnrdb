package io.github.christiancg.lwnrdb.client.error;

public class PoolExhaustedException extends LwnrdbException {
    public PoolExhaustedException(String message) {
        super(message, null);
    }
}

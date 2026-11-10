package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.RetryPolicy;
import io.github.christiancg.lwnrdb.client.error.ConnectionLostException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.error.UnavailableException;
import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import java.time.Duration;
import tools.jackson.databind.node.ObjectNode;

public final class PooledChannel implements RequestChannel {
    private final ConnectionPool pool;
    private final RetryPolicy retryPolicy;
    private final Duration requestTimeout;
    private final Backoff backoff;

    public PooledChannel(ConnectionPool pool, RetryPolicy retryPolicy, Duration requestTimeout, Backoff backoff) {
        this.pool = pool;
        this.retryPolicy = retryPolicy;
        this.requestTimeout = requestTimeout;
        this.backoff = backoff;
    }

    @Override
    public ObjectNode send(Operation operation, ObjectNode request) {
        var attempt = 1;
        while (true) {
            try {
                return once(request);
            } catch (LwnrdbException e) {
                if (attempt >= retryPolicy.maxAttempts() || !retryable(operation, e)) {
                    throw e;
                }
                backoff.pause(retryPolicy.backoffBefore(attempt));
                attempt++;
            }
        }
    }

    private ObjectNode once(ObjectNode request) {
        try (var lease = pool.borrow()) {
            try {
                return ResponseDecoder.check(lease.connection().request(request, requestTimeout));
            } catch (ConnectionLostException e) {
                lease.discard();
                throw e;
            }
        }
    }

    private static boolean retryable(Operation operation, LwnrdbException e) {
        if (e instanceof ConnectionLostException) {
            return operation.isRead();
        }
        return e instanceof UnavailableException && e.errorCode().map(ErrorCode::isRetryable).orElse(false);
    }
}

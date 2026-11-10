Everything `LwnrdbClient.connect` needs: where the server is, how to authenticate, and how the
connection pool, timeouts and retries behave.

```java
ClientSettings settings = ClientSettings.builder()
        .host("localhost", 8989)
        .credentials("alice", password)
        .poolSize(8)
        .build();
```

## DEFAULT_POOL_SIZE

The default maximum number of pooled connections, 8.

## DEFAULT_CONNECT_TIMEOUT

The default time to open a socket and finish a TLS handshake, 10 seconds.

## DEFAULT_REQUEST_TIMEOUT

The default time to wait for one reply, 330 seconds: just above the server's default `aggregateTimeoutMs`.

## DEFAULT_ACQUIRE_TIMEOUT

The default time to wait for a free pooled connection, 30 seconds.

## DEFAULT_SHUTDOWN_TIMEOUT

The default time `LwnrdbClient.close()` waits for borrowed connections, 10 seconds.

## builder()

Starts a new builder.

@return a builder with every default set and no host

## hosts()

The servers to connect to, tried in turn when a connection cannot be opened.

@return the hosts, never empty

## username()

@return the user to authenticate as, empty for an unauthenticated client

## password()

@return a copy of the password, or `null` when no credentials are set

## tls()

@return the TLS settings, empty for a plaintext connection

## poolSize()

@return the maximum number of pooled connections

## connectTimeout()

@return the time allowed to open a socket and finish the TLS handshake

## requestTimeout()

The time allowed for one reply. A request that times out closes its connection, because a late reply would
be read as the answer to the next request; the outcome of a write that timed out is unknown.

@return the per-request timeout

## acquireTimeout()

@return the time a caller waits for a free pooled connection before `PoolExhaustedException`

## shutdownTimeout()

@return the time `close()` waits for borrowed connections to come back

## retryPolicy()

@return how retryable `503` refusals and lost connections on reads are retried

## maxReplyBytes()

The largest reply the client reads. A larger reply fails only its own request; the connection stays usable.

@return the limit in bytes

## objectMapper()

@return the caller's Jackson mapper that POJO mapping extends, empty for the client's own

## temporalZone()

@return the zone that converts `Instant`, `OffsetDateTime` and `ZonedDateTime` to the server's zone-less datetime

How a `LwnrdbClient` connects, authenticates and behaves.

Every property has a default, so `new ClientSettings()` connects to `localhost:8989` without credentials. Values are validated by `LwnrdbClient.ConnectAsync`.

## ClientSettings()
Creates settings with every property at its default.

## AcquireTimeout
The time to wait for a free pooled connection before `PoolExhaustedException`. Positive; 30 seconds by default.

## ConnectTimeout
The time to open a socket and finish the TLS and authentication handshake. Positive; 10 seconds by default.

## Credentials
The user every pooled connection authenticates as. When null, connections stay unauthenticated, which the server only allows for `SERVER_INFO`.

## Hosts
The servers to connect to, tried in order. At least one; `localhost:8989` by default.

## Json
The `System.Text.Json` options used to map typed documents. The client works on a copy and adds its converters for the custom value types, the `_id` alias of an `Id` member and `[LwnrdbVector]`. When null, camel-case names and strict number handling are used.

## MaxReplyBytes
The largest reply the client reads; a bigger one is discarded and fails the request. Positive; 256 MB by default.

## PoolSize
The maximum number of pooled connections. At least 1; 8 by default.

## RequestTimeout
The time to wait for one reply. When it passes, the connection is closed and the request fails with `RequestTimeoutException`, since its outcome is unknown. Positive; 330 seconds by default.

## RetryPolicy
How the client retries refusals that are safe to retry. `RetryPolicy.None` sends every request once.

## ShutdownTimeout
The time `DisposeAsync` waits for borrowed connections before closing them. Positive; 10 seconds by default.

## TemporalZone
The time zone the stored date-times are in. Without it, a `DateTime` of kind `Utc` or `Local` and a `DateTimeOffset` are refused on write; with it they are converted to this zone's wall-clock time, and read values stay `DateTimeKind.Unspecified`.

## Tls
Connects over TLS when set. The server must run with `tlsEnabled=true`.

# ClientSettings

How the client connects. Durations are seconds.

| Setting | Default |
|---|---|
| `hosts` | `(ServerAddress("localhost", 8989),)` |
| `credentials` | none |
| `tls` | none |
| `pool_size` | 8 |
| `connect_timeout` | 10.0 |
| `request_timeout` | 330.0 |
| `acquire_timeout` | 30.0 |
| `shutdown_timeout` | 10.0 |
| `max_reply_bytes` | 256 MiB |
| `retry_policy` | `DEFAULT_RETRY_POLICY` |
| `temporal_zone` | none: an aware datetime is refused |

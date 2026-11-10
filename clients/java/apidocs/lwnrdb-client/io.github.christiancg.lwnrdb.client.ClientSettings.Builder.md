Builds `ClientSettings`. Every setter validates its argument and returns the builder.

## host(String, int)

Adds a server.

@param host the host name or address
@param port the client port, `8989` by default on the server
@return this builder

## host(ServerAddress)

Adds a server.

@param address the server
@return this builder

## credentials(String, char[])

Sets the user every pooled connection authenticates as.

@param username the user
@param password the password, copied
@return this builder

## credentials(String, String)

Sets the user every pooled connection authenticates as.

@param username the user
@param password the password
@return this builder

## tls(TlsSettings)

Connects over TLS. The server must run with `tlsEnabled=true`.

@param tls how to trust the server's certificate
@return this builder

## poolSize(int)

@param poolSize the maximum number of pooled connections, at least 1
@return this builder

## connectTimeout(Duration)

@param timeout the time to open a socket and finish the handshake, positive
@return this builder

## requestTimeout(Duration)

@param timeout the time to wait for one reply, positive
@return this builder

## acquireTimeout(Duration)

@param timeout the time to wait for a free pooled connection, positive
@return this builder

## shutdownTimeout(Duration)

@param timeout the time `close()` waits for borrowed connections, positive
@return this builder

## retryPolicy(RetryPolicy)

@param retryPolicy how refusals are retried; `RetryPolicy.none()` sends each request once
@return this builder

## maxReplyBytes(long)

@param maxReplyBytes the largest reply read, positive
@return this builder

## objectMapper(JsonMapper)

Uses the caller's Jackson 3 mapper for POJO mapping. The client registers its own module on a copy, so the
caller's mapper is never changed.

@param objectMapper the mapper to extend
@return this builder

## temporalZone(ZoneId)

Allows zoned date-times: they are converted to this zone's local date-time on write, and back on read.

@param zone the zone the stored datetimes are in
@return this builder

## build()

@return the settings
@throws IllegalArgumentException when no host was added

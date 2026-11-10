# ClientSettings

What `LwnrdbClient.connect` takes. Every field is optional.

## hosts

The servers, tried in turn when one refuses. `localhost:8989` by default.

## credentials

The user each connection logs in as. None by default.

## tls

TLS for every connection. Plain TCP when absent.

## poolSize

How many requests run at once. 8 by default.

## connectTimeoutMs

How long opening a connection may take. 10 s by default.

## requestTimeoutMs

How long a reply may take before its connection is closed. 330 s by default.

## acquireTimeoutMs

How long a request waits for a pooled connection. 30 s by default.

## shutdownTimeoutMs

How long `close()` waits for requests in flight. 10 s by default.

## maxReplyBytes

The largest reply line accepted. 256 MB by default.

## retryPolicy

How refusals that are safe to retry are retried. `DEFAULT_RETRY_POLICY` by default.

## temporalZone

An IANA zone that lets a JS `Date` be stored as a `LocalDateTime`. None by default.

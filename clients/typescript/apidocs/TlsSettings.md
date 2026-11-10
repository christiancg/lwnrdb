# TlsSettings

How a client connects over TLS. Without `ca` or `insecure`, the system's certificate authorities
are trusted. Export a server's certificate as PEM with `keytool -exportcert -rfc`.

## ca

PEM certificates to trust instead of the system's.

## servername

The name the certificate must carry, when it differs from the host.

## insecure

Trust any certificate and skip the host-name check. Development only; it emits a warning.

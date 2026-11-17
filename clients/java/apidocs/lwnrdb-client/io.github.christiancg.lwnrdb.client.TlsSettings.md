How the client trusts the server's TLS certificate.

## DEFAULT_TRUST_STORE_TYPE

The default trust-store type, `PKCS12`.

## systemTrust()

@return settings that trust the JVM's default certificate authorities and check the host name

## trustStore(Path, char[])

@param path a PKCS12 trust store holding the server's certificate or its CA
@param password the store's password
@return settings that trust that store and check the host name

## trustStore(Path, char[], String)

@param path a trust store
@param password the store's password
@param type the store's type, such as `PKCS12` or `JKS`
@return settings that trust that store and check the host name

## insecure()

Trusts any certificate and skips the host-name check. For development only: it logs a warning.

@return insecure settings

## trustStorePath()

@return the trust store, empty for the system trust

## trustStorePassword()

@return a copy of the trust store's password, or `null`

## trustStoreType()

@return the trust store's type, or `null` for the system trust

## isInsecure()

@return `true` when certificate and host-name checks are off

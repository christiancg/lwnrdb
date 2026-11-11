How the client trusts the server's certificate when `ClientSettings.Tls` is set.

With no property set, the server's certificate must chain to the operating system's trust store and match the host name. For the self-signed certificate the server generates in development, trust it with `TrustedCertificates` or, for throwaway setups only, set `Insecure`.

## TlsSettings()
Creates settings that use the system trust store.

## LoadPem(string)
Loads the certificates of a PEM file, for use as `TrustedCertificates`.

@param path the path of a PEM file
@returns the certificates in the file

## LoadPkcs12(string, string)
Loads the certificates of a PKCS12 file, for use as `TrustedCertificates`.

@param path the path of a PKCS12 file
@param password the file's password, or null when it has none
@returns the certificates in the file

## Insecure
When true, the server's certificate is not verified at all. Never use it outside a throwaway setup.

## ServerName
The name the certificate must match, when it differs from the host being connected to.

## TrustedCertificates
The certificates to trust in addition to or instead of the system store, which lets a private or self-signed certificate authority validate the server.

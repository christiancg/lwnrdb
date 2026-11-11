using System.Security.Cryptography.X509Certificates;

namespace Lwnrdb.Client;

public sealed record TlsSettings
{
    public X509Certificate2Collection? TrustedCertificates { get; init; }

    public string? ServerName { get; init; }

    public bool Insecure { get; init; }

    public static X509Certificate2Collection LoadPem(string path)
    {
        var certificates = new X509Certificate2Collection();
        certificates.ImportFromPemFile(path);
        return certificates;
    }

    public static X509Certificate2Collection LoadPkcs12(string path, string? password) =>
        X509CertificateLoader.LoadPkcs12CollectionFromFile(path, password);
}

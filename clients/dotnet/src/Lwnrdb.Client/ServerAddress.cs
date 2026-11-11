using System;

namespace Lwnrdb.Client;

public sealed record ServerAddress
{
    public const int DefaultPort = 8989;
    private const int MaxPort = 65_535;

    public ServerAddress(string host, int port = DefaultPort)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(host);
        ArgumentOutOfRangeException.ThrowIfLessThan(port, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(port, MaxPort);
        Host = host;
        Port = port;
    }

    public string Host { get; }

    public int Port { get; }

    public override string ToString() => $"{Host}:{Port}";
}

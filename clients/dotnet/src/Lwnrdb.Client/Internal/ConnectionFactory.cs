using System;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;

namespace Lwnrdb.Client.Internal;

internal delegate Task<Stream> StreamOpener(ServerAddress address, ClientSettings settings, CancellationToken ct);

internal sealed class ConnectionFactory
{
    private const string InsecureWarning =
        "LWNRDB TLS certificate and hostname checks are disabled; use this only in development";

    private readonly RequestEncoder _encoder;
    private readonly StreamOpener _opener;
    private int _nextHost;

    public ConnectionFactory(ClientSettings settings, RequestEncoder encoder, StreamOpener? opener = null)
    {
        Settings = settings;
        _encoder = encoder;
        _opener = opener ?? OpenStreamAsync;
        if (settings.Tls?.Insecure == true)
        {
            Trace.TraceWarning(InsecureWarning);
        }
    }

    public ClientSettings Settings { get; }

    public async Task<WireConnection> OpenAsync(CancellationToken ct)
    {
        var hosts = Settings.Hosts;
        ConnectionLostException? last = null;
        for (var remaining = hosts.Count; remaining > 0; remaining--)
        {
            var address = hosts[(int)((uint)Interlocked.Increment(ref _nextHost) % hosts.Count)];
            Stream stream;
            try
            {
                using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
                deadline.CancelAfter(Settings.ConnectTimeout);
                stream = await _opener(address, Settings, deadline.Token).ConfigureAwait(false);
            }
            catch (Exception error) when (!ct.IsCancellationRequested && error is not LwnrdbException)
            {
                var reason = error is OperationCanceledException
                    ? $"no connection within {Settings.ConnectTimeout.TotalMilliseconds} ms"
                    : error.Message;
                last = new ConnectionLostException($"could not connect to {address}: {reason}", error);
                continue;
            }
            return await AuthenticatedAsync(new WireConnection(stream, Settings.MaxReplyBytes, address.ToString()), ct)
                .ConfigureAwait(false);
        }
        throw last ?? new ConnectionLostException("no host to connect to");
    }

    private async Task<WireConnection> AuthenticatedAsync(WireConnection connection, CancellationToken ct)
    {
        var credentials = Settings.Credentials;
        if (credentials is null)
        {
            return connection;
        }
        try
        {
            ResponseDecoder.Check(await connection.RequestAsync(
                RequestEncoder.Authenticate(credentials.Username, credentials.Password),
                Settings.RequestTimeout,
                ct).ConfigureAwait(false));
            return connection;
        }
        catch
        {
            connection.Close();
            throw;
        }
    }

    private static async Task<Stream> OpenStreamAsync(ServerAddress address, ClientSettings settings, CancellationToken ct)
    {
        var socket = new Socket(SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        try
        {
            socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
            await socket.ConnectAsync(address.Host, address.Port, ct).ConfigureAwait(false);
            var stream = new NetworkStream(socket, ownsSocket: true);
            return settings.Tls is null ? stream : await SecuredAsync(stream, address, settings.Tls, ct).ConfigureAwait(false);
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    [SuppressMessage("Security", "CA5359")]
    private static async Task<Stream> SecuredAsync(Stream stream, ServerAddress address, TlsSettings tls, CancellationToken ct)
    {
        var options = new SslClientAuthenticationOptions
        {
            TargetHost = tls.ServerName ?? (IPAddress.TryParse(address.Host, out _) ? string.Empty : address.Host),
        };
        if (tls.Insecure)
        {
            options.RemoteCertificateValidationCallback = static (_, _, _, _) => true;
        }
        else if (tls.TrustedCertificates is { Count: > 0 } trusted)
        {
            var policy = new X509ChainPolicy
            {
                TrustMode = X509ChainTrustMode.CustomRootTrust,
                RevocationMode = X509RevocationMode.NoCheck,
            };
            policy.CustomTrustStore.AddRange(trusted);
            options.CertificateChainPolicy = policy;
        }
        var secured = new SslStream(stream, leaveInnerStreamOpen: false);
        try
        {
            await secured.AuthenticateAsClientAsync(options, ct).ConfigureAwait(false);
            return secured;
        }
        catch
        {
            await secured.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }
}

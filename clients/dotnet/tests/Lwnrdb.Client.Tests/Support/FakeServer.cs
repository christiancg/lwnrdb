using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace Lwnrdb.Client.Tests.Support;

internal delegate void Responder(JsonObject request, FakeSession session);

internal sealed class FakeSession(TcpClient client)
{
    private readonly Lock _writeLock = new();

    public List<JsonObject> Requests { get; } = [];

    public NetworkStream Stream { get; } = client.GetStream();

    public void Send(JsonObject frame) => SendRaw(frame.ToJsonString() + "\n");

    public void SendRaw(string text) => SendRaw(Encoding.UTF8.GetBytes(text));

    public void SendRaw(byte[] bytes)
    {
        lock (_writeLock)
        {
            try
            {
                Stream.Write(bytes);
            }
            catch (IOException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
        }
    }

    public void Close() => client.Dispose();
}

internal sealed class FakeServer : IAsyncDisposable
{
    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
    private readonly CancellationTokenSource _stop = new();
    private readonly ConcurrentQueue<JsonObject> _requests = new();
    private readonly SemaphoreSlim _arrived = new(0);

    private FakeServer(Responder responder)
    {
        Responder = responder;
        _listener.Start();
        _ = Task.Run(AcceptAsync);
    }

    public static Responder OkToEverything { get; } = (request, session) => session.Send(RecordingChannel.Ok(request));

    public Responder Responder { get; set; }

    public ConcurrentQueue<FakeSession> Sessions { get; } = new();

    public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

    public IReadOnlyList<ServerAddress> Hosts => [new("127.0.0.1", Port)];

    public IReadOnlyList<JsonObject> Requests => [.. _requests];

    public static FakeServer Start(Responder? responder = null) => new(responder ?? OkToEverything);

    public ClientSettings Settings(Func<ClientSettings, ClientSettings>? adjust = null)
    {
        var settings = new ClientSettings
        {
            Hosts = Hosts,
            RequestTimeout = TimeSpan.FromSeconds(5),
            AcquireTimeout = TimeSpan.FromSeconds(5),
            ShutdownTimeout = TimeSpan.FromSeconds(2),
            RetryPolicy = RetryPolicy.None,
        };
        return adjust is null ? settings : adjust(settings);
    }

    public async Task<JsonObject> NextRequestAsync(string type, TimeSpan? within = null)
    {
        var deadline = DateTime.UtcNow + (within ?? TimeSpan.FromSeconds(5));
        while (DateTime.UtcNow < deadline)
        {
            var match = _requests.FirstOrDefault(request => request["type"]?.GetValue<string>() == type);
            if (match is not null)
            {
                return match;
            }
            await _arrived.WaitAsync(TimeSpan.FromMilliseconds(50));
        }
        throw new TimeoutException($"no {type} request arrived");
    }

    public int Count(string type) => _requests.Count(request => request["type"]?.GetValue<string>() == type);

    public async ValueTask DisposeAsync()
    {
        await _stop.CancelAsync();
        _listener.Stop();
        foreach (var session in Sessions)
        {
            session.Close();
        }
    }

    private async Task AcceptAsync()
    {
        while (!_stop.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(_stop.Token);
            }
            catch (Exception error) when (error is OperationCanceledException or ObjectDisposedException or SocketException)
            {
                return;
            }
            var session = new FakeSession(client);
            Sessions.Enqueue(session);
            _ = Task.Run(() => ReadAsync(session));
        }
    }

    private async Task ReadAsync(FakeSession session)
    {
        using var reader = new StreamReader(session.Stream, Encoding.UTF8);
        try
        {
            while (await reader.ReadLineAsync(_stop.Token) is { } line)
            {
                var request = JsonNode.Parse(line)!.AsObject();
                session.Requests.Add(request);
                _requests.Enqueue(request);
                _arrived.Release();
                Responder(request, session);
            }
        }
        catch (Exception error) when (error is IOException or ObjectDisposedException or OperationCanceledException)
        {
            return;
        }
    }
}

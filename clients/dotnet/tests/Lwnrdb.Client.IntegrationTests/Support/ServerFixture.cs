using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Xunit;

namespace Lwnrdb.Client.IntegrationTests.Support;

public abstract class ServerFixture : IAsyncLifetime
{
    private ServerProcess? _server;
    private LwnrdbClient? _client;

    public ServerProcess Server => _server ?? throw new InvalidOperationException("the server is not started");

    public LwnrdbClient Client => _client ?? throw new InvalidOperationException("the client is not connected");

    protected virtual IReadOnlyDictionary<string, string> Config => new Dictionary<string, string>();

    public ClientSettings Settings(Func<ClientSettings, ClientSettings>? adjust = null)
    {
        var settings = new ClientSettings
        {
            Hosts = [new ServerAddress("localhost", Server.Port)],
            Credentials = new Credentials(ServerProcess.Admin, ServerProcess.Password),
            RequestTimeout = TimeSpan.FromSeconds(60),
        };
        return adjust is null ? settings : adjust(settings);
    }

    public async ValueTask InitializeAsync()
    {
        _server = await ServerProcess.StartAsync(Config);
        _client = await LwnrdbClient.ConnectAsync(Settings(AdjustClient));
        await SeedAsync();
    }

    public async ValueTask DisposeAsync()
    {
        if (_client is not null)
        {
            await _client.DisposeAsync();
        }
        if (_server is not null)
        {
            await _server.DisposeAsync();
        }
        GC.SuppressFinalize(this);
    }

    protected virtual ClientSettings AdjustClient(ClientSettings settings) => settings;

    protected virtual Task SeedAsync() => Task.CompletedTask;
}

public sealed class DefaultServer : ServerFixture;

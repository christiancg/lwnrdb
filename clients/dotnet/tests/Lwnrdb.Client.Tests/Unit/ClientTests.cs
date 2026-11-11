using System;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Lwnrdb.Client.Tests.Support;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit;

public sealed class ClientTests : IAsyncLifetime
{
    private FakeServer _server = null!;

    public ValueTask InitializeAsync()
    {
        _server = FakeServer.Start((request, session) => session.Send(RecordingChannel.Ok(request, new JsonObject
        {
            ["databases"] = new JsonArray("shop"),
            ["server"] = new JsonObject { ["state"] = "RUNNING", ["version"] = "1.0" },
            ["stats"] = new JsonObject { ["databases"] = 1 },
        })));
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync() => _server.DisposeAsync();

    [Fact]
    public async Task ClientCallsReachTheServer()
    {
        await using var client = await LwnrdbClient.ConnectAsync(_server.Settings(settings => settings with { Credentials = new Credentials("alice", "pw") }));
        Assert.Equal(["shop"], await client.ListDatabasesAsync());
        var info = await client.ServerInfoAsync();
        Assert.Equal(("RUNNING", "1.0"), (info.State, info.Version));
        Assert.Equal(1, (await client.DatabaseStatsAsync()).GetDouble("databases"));
        await client.ChangePasswordAsync("pw", "new");
        Assert.Equal("alice", (await _server.NextRequestAsync("SET_PASSWORD"))["username"]!.GetValue<string>());
        Assert.Equal("alice", (await _server.NextRequestAsync("AUTHENTICATE"))["username"]!.GetValue<string>());
        Assert.Equal("pw", client.Settings.Credentials!.Password);
        Assert.NotNull(client.Admin());
        await client.TransactionAsync((transaction, ct) => transaction.Collection("shop", "orders").DeleteAsync("o-1", ct));
        Assert.Equal(1, _server.Count("COMMIT_TRANSACTION"));
    }

    [Fact]
    public async Task ChangingThePasswordNeedsCredentials()
    {
        await using var client = await LwnrdbClient.ConnectAsync(_server.Settings());
        await Assert.ThrowsAsync<InvalidOperationException>(() => client.ChangePasswordAsync("a", "b"));
    }

    [Fact]
    public async Task SyncTerminalsBlockAndReturn()
    {
        await using var client = await LwnrdbClient.ConnectAsync(_server.Settings());
        var orders = client.Database("shop").Collection<Order>("orders").AsQueryable();
        Assert.Empty(orders.Where(o => o.Paid).ToList());
        Assert.Equal(0, orders.Count());
        Assert.Empty(((IQueryable)orders).Provider.Execute<System.Collections.Generic.IEnumerable<Order>>(orders.Expression));
        Assert.Empty((System.Collections.IEnumerable)((IQueryable)orders).Provider.Execute(orders.Expression)!);
        Assert.Equal(typeof(Order), orders.ElementType);
        Assert.Equal("shop.orders", orders.ToString());
        Assert.NotNull(((IQueryable)orders).Provider.CreateQuery(orders.Where(o => o.Paid).Expression));
    }

    [Fact]
    public async Task ARequestAfterDisposalFailsAtOnce()
    {
        var client = await LwnrdbClient.ConnectAsync(_server.Settings());
        await client.DisposeAsync();
        await Assert.ThrowsAsync<ObjectDisposedException>(() => client.ListDatabasesAsync());
        await client.DisposeAsync();
    }

    [Fact]
    public async Task TlsSettingsLoadCertificates()
    {
        using var key = System.Security.Cryptography.RSA.Create(2048);
        var request = new System.Security.Cryptography.X509Certificates.CertificateRequest(
            "CN=localhost", key, System.Security.Cryptography.HashAlgorithmName.SHA256, System.Security.Cryptography.RSASignaturePadding.Pkcs1);
        using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(1));
        var folder = System.IO.Directory.CreateTempSubdirectory();
        try
        {
            var pem = System.IO.Path.Combine(folder.FullName, "server.pem");
            await System.IO.File.WriteAllTextAsync(pem, certificate.ExportCertificatePem());
            Assert.Single(TlsSettings.LoadPem(pem));
            var pfx = System.IO.Path.Combine(folder.FullName, "server.p12");
            await System.IO.File.WriteAllBytesAsync(pfx, certificate.Export(System.Security.Cryptography.X509Certificates.X509ContentType.Pkcs12, "pw"));
            Assert.Single(TlsSettings.LoadPkcs12(pfx, "pw"));
        }
        finally
        {
            folder.Delete(recursive: true);
        }
    }

    [Fact]
    public async Task ATlsClientFailsAgainstAPlainServer()
    {
        var settings = _server.Settings(settings => settings with { Tls = new TlsSettings { Insecure = true }, ConnectTimeout = TimeSpan.FromSeconds(2) });
        await Assert.ThrowsAsync<Errors.ConnectionLostException>(() => LwnrdbClient.ConnectAsync(settings));
        var trusted = settings with { Tls = new TlsSettings { TrustedCertificates = [], ServerName = "localhost" } };
        await Assert.ThrowsAsync<Errors.ConnectionLostException>(() => LwnrdbClient.ConnectAsync(trusted));
    }
}

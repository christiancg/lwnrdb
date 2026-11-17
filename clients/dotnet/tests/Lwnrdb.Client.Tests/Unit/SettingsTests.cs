using System;
using System.Threading.Tasks;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit;

public sealed class SettingsTests
{
    [Fact]
    public void TheDefaultsEqualTheOtherClients()
    {
        var settings = new ClientSettings();
        Assert.Equal([new ServerAddress("localhost", 8989)], settings.Hosts);
        Assert.Equal(8, settings.PoolSize);
        Assert.Equal(TimeSpan.FromSeconds(10), settings.ConnectTimeout);
        Assert.Equal(TimeSpan.FromSeconds(330), settings.RequestTimeout);
        Assert.Equal(TimeSpan.FromSeconds(30), settings.AcquireTimeout);
        Assert.Equal(TimeSpan.FromSeconds(10), settings.ShutdownTimeout);
        Assert.Equal(256L * 1024 * 1024, settings.MaxReplyBytes);
        Assert.Equal(RetryPolicy.Default, settings.RetryPolicy);
        Assert.Equal(1, RetryPolicy.None.MaxAttempts);
    }

    public static TheoryData<ClientSettings> InvalidSettings() =>
    [
        new ClientSettings { PoolSize = 0 },
        new ClientSettings { Hosts = [] },
        new ClientSettings { RequestTimeout = TimeSpan.Zero },
        new ClientSettings { ConnectTimeout = TimeSpan.FromMilliseconds(-1) },
        new ClientSettings { AcquireTimeout = TimeSpan.FromDays(40) },
        new ClientSettings { ShutdownTimeout = TimeSpan.Zero },
        new ClientSettings { MaxReplyBytes = 0 },
        new ClientSettings { RetryPolicy = null! },
        new ClientSettings { Hosts = [null!] },
    ];

    [Theory]
    [MemberData(nameof(InvalidSettings))]
    public async Task InvalidSettingsAreRefusedNamingTheSetting(ClientSettings settings) =>
        await Assert.ThrowsAnyAsync<ArgumentException>(() => LwnrdbClient.ConnectAsync(settings));

    [Fact]
    public void AddressesAndCredentialsAreValidated()
    {
        Assert.Throws<ArgumentException>(() => new ServerAddress(" "));
        Assert.Throws<ArgumentOutOfRangeException>(() => new ServerAddress("h", 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => new ServerAddress("h", 65_536));
        Assert.Equal("h:1", new ServerAddress("h", 1).ToString());
        Assert.Throws<ArgumentException>(() => new Credentials("", "p"));
        Assert.DoesNotContain("secret", new Credentials("alice", "secret").ToString(), StringComparison.Ordinal);
    }
}

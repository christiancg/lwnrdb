using System;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit.Internal;

public sealed class NamesTests
{
    [Theory]
    [InlineData("abc")]
    [InlineData("A_b-9")]
    public void ValidNamesPass(string name)
    {
        Assert.Equal(name, Names.Database(name));
        Assert.Equal(name, Names.Collection(name));
    }

    [Theory]
    [InlineData("ab")]
    [InlineData("has space")]
    [InlineData("dots.not")]
    [InlineData(null)]
    public void InvalidNamesAreRefusedBeforeAnyRequest(string? name)
    {
        Assert.Throws<ArgumentException>(() => Names.Database(name));
        Assert.Throws<ArgumentException>(() => Names.Collection(name));
    }

    [Fact]
    public async Task IdsAllowOneToSixtyFourCharacters()
    {
        Assert.Equal("a", Names.DocumentId("a"));
        Assert.Throws<ArgumentException>(() => Names.DocumentId(""));
        Assert.Throws<ArgumentException>(() => Names.DocumentId(new string('a', 65)));
        await Assert.ThrowsAsync<ArgumentException>(() => new Support.Recorder().Orders.FindByIdAsync("bad id"));
    }
}

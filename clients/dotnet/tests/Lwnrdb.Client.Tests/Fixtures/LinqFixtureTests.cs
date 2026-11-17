using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Lwnrdb.Client.Tests.Support;
using Xunit;

namespace Lwnrdb.Client.Tests.Fixtures;

public sealed class LinqFixtureTests
{
    private static readonly Dictionary<string, FixtureCall> Cases = LinqFixtureCases.All();

    public static TheoryData<string> CaseNames() => [.. Cases.Keys.Order()];

    [Theory]
    [MemberData(nameof(CaseNames))]
    public async Task EveryLinqCaseProducesItsFixture(string name)
    {
        var recorder = new Recorder();
        await Cases[name](recorder);
        var expected = ProtocolFixtures.Request(name).Body["request"];
        var actual = recorder.Channel.Last;
        Assert.True(JsonTrees.Equivalent(expected, actual), $"{name}\nexpected {expected?.ToJsonString()}\nactual   {actual.ToJsonString()}");
    }

    [Fact]
    public void LinqCasesNameExistingFixtures()
    {
        var names = ProtocolFixtures.Requests().Select(fixture => fixture.Name).ToHashSet();
        Assert.All(Cases.Keys, name => Assert.Contains(name, names));
    }
}

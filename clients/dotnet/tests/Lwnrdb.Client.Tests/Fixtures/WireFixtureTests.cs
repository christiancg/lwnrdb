using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Lwnrdb.Client.Protocol;
using Lwnrdb.Client.Tests.Support;
using Xunit;

namespace Lwnrdb.Client.Tests.Fixtures;

public sealed class WireFixtureTests
{
    private static readonly Dictionary<string, FixtureCall> Cases = WireFixtureCases.All();

    public static TheoryData<string> FixtureNames() => [.. ProtocolFixtures.Requests().Select(fixture => fixture.Name)];

    [Theory]
    [MemberData(nameof(FixtureNames))]
    public async Task EveryRequestFixtureIsProducedByItsBuilderCall(string name)
    {
        Assert.True(Cases.TryGetValue(name, out var call), $"{name} has no builder mapping");
        var recorder = new Recorder();
        await call(recorder);
        var expected = ProtocolFixtures.Request(name).Body["request"];
        var actual = recorder.Channel.Last;
        Assert.True(JsonTrees.Equivalent(expected, actual), $"{name}\nexpected {expected?.ToJsonString()}\nactual   {actual.ToJsonString()}");
    }

    [Fact]
    public void EveryMappingNamesAnExistingFixture()
    {
        var names = ProtocolFixtures.Requests().Select(fixture => fixture.Name).ToHashSet();
        Assert.All(Cases.Keys, name => Assert.Contains(name, names));
    }

    [Fact]
    public async Task EveryOperationIsProducedBySomeMapping()
    {
        var produced = new SortedSet<string>();
        foreach (var call in Cases.Values)
        {
            var recorder = new Recorder();
            await call(recorder);
            produced.UnionWith(recorder.Channel.Requests.Select(request => request["type"]!.GetValue<string>()));
        }
        Assert.Equal(Operation.All.Select(operation => operation.Name).Order(), produced);
    }
}

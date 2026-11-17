using System;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;
using Xunit;
using static Lwnrdb.Client.Query.Filters;

namespace Lwnrdb.Client.Tests.Unit.Query;

public sealed class QueryTests
{
    private readonly Recorder _recorder = new();

    [Fact]
    public void AStepReturnsANewQuery()
    {
        var empty = _recorder.Orders.Query();
        var filtered = empty.Filter(Field("a").Eq(1));
        Assert.Empty(empty.Steps);
        Assert.Single(filtered.Steps);
        Assert.False(filtered.IsDirtyRead);
        Assert.True(filtered.DirtyRead().IsDirtyRead);
        Assert.NotEqual(empty, filtered);
        Assert.Equal(filtered, empty.Filter(Field("a").Eq(1)));
        Assert.Equal(filtered.GetHashCode(), empty.Filter(Field("a").Eq(1)).GetHashCode());
        Assert.Contains("FilterStep", filtered.DirtyRead().ToString(), StringComparison.Ordinal);
        Assert.False(filtered.Equals((object?)null));
    }

    [Fact]
    public void InvalidStepsAreRefused()
    {
        var query = _recorder.Orders.Query();
        Assert.Throws<ArgumentOutOfRangeException>(() => query.Limit(0));
        Assert.Throws<ArgumentOutOfRangeException>(() => query.Skip(-1));
        Assert.Throws<ArgumentException>(() => query.Map());
        Assert.Throws<ArgumentException>(() => query.GroupBy(""));
        Assert.Throws<ArgumentNullException>(() => query.Filter(null!));
        Assert.Throws<ArgumentOutOfRangeException>(() => query.Sort("a", (Client.Query.Order)7));
        Assert.Throws<ArgumentException>(() => query.Join("c", "", "r", "a"));
        Assert.Throws<ArgumentException>(() => query.Distinct(""));
        Assert.Throws<ArgumentException>(() => query.Reduce("s", 1, ""));
    }

    [Fact]
    public void FilterBuildersValidateTheirArguments()
    {
        Assert.Throws<ArgumentException>(() => And());
        Assert.Throws<ArgumentNullException>(() => Or(null!));
        Assert.Throws<ArgumentException>(() => Field(""));
        Assert.Throws<ArgumentOutOfRangeException>(() => Distance("f", new GeoPoint(0, 0), GeoDistanceComparator.Equal, -1));
        Assert.Throws<ArgumentException>(() => Within("f", new GeoPoint(0, 0)));
        Assert.Throws<ArgumentOutOfRangeException>(() => Nearest("f", Vector.Of(1), 0));
        Assert.Throws<ArgumentException>(() => Script(""));
        Assert.Throws<ArgumentException>(() => MidOps.Sum());
        Assert.Throws<ArgumentException>(() => MidOps.Cast("f", CastType.JsonCustom));
        Assert.Throws<ArgumentException>(() => MidOps.Cast("f", (CustomType)9));
        Assert.Throws<ArgumentNullException>(() => MapOps.Set("f", null!));
        Assert.True(MapOps.Remove("f").Removes);
        Assert.False(MapOps.Set("f", MidOps.Abs("g")).Removes);
        Assert.Equal("f", Field("f").Field);
    }

    [Fact]
    public async Task FirstOrDefaultAddsALimitOfOne()
    {
        Assert.Null(await _recorder.Orders.Query().FirstOrDefaultAsync());
        Assert.Equal("[{\"type\":\"LIMIT\",\"limit\":1}]", _recorder.Channel.Last["aggregationSteps"]!.ToJsonString());
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, new JsonObject { ["results"] = new JsonArray(new JsonObject { ["_id"] = "a" }) });
        Assert.Equal("a", (await _recorder.Orders.Query().FirstOrDefaultAsync())!.Id);
    }

    [Fact]
    public async Task CountReadsTheCountAndAnswersZeroOnAnEmptyResult()
    {
        Assert.Equal(0, await _recorder.Orders.Query().CountAsync());
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, new JsonObject { ["results"] = new JsonArray(new JsonObject { ["count"] = 12 }) });
        Assert.Equal(12, await _recorder.Orders.Query().CountAsync());
    }

    [Fact]
    public async Task NoResultsIsAnEmptyListAndOtherErrorsPropagate()
    {
        _recorder.Channel.Replies = request => RecordingChannel.Error(request, "NOT_FOUND", "404-3");
        Assert.Empty(await _recorder.Orders.Query().ToListAsync());
        _recorder.Channel.Replies = request => RecordingChannel.Error(request, "NOT_FOUND", "404-11");
        await Assert.ThrowsAsync<Errors.NotFoundException>(() => _recorder.Orders.Query().ToListAsync());
    }

    [Fact]
    public async Task AnalyzeReturnsTheAnalysis()
    {
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, new JsonObject
        {
            ["results"] = new JsonArray(new JsonObject { ["_id"] = "a" }),
            ["analyzeResult"] = new JsonObject { ["scanned"] = 3 },
        });
        var result = await _recorder.Orders.Query().AnalyzeAsync();
        Assert.Equal(3, result.Analysis.GetDouble("scanned"));
        Assert.Single(result.Results);
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request);
        Assert.Equal(Document.Empty, (await _recorder.Orders.Query().AnalyzeAsync()).Analysis);
    }
}

using System;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;
using Xunit;

namespace Lwnrdb.Client.Tests.Linq;

public sealed class ConstantEncoderTests
{
    private readonly LinqHarness _harness = new();

    private static FieldFilter TaggedFilter(LinqHarness harness, System.Linq.Expressions.Expression<Func<Tagged, bool>> predicate)
    {
        var tagged = new Database(harness.Context, "shop").Collection<Tagged>("tagged");
        return (FieldFilter)((FilterStep)tagged.AsQueryable().Where(predicate).ToQuery().Steps[0]).Filter;
    }

    [Fact]
    public void AnEnumIsComparedAsItsStoredForm()
    {
        Assert.Equal(1, TaggedFilter(_harness, t => t.Plain == OrderStatus.Closed).Value!.GetValue<int>());
        Assert.Equal("Closed", TaggedFilter(_harness, t => t.Stage == OrderStatus.Closed).Value!.GetValue<string>());
        var strings = new LinqHarness(json: new JsonSerializerOptions { Converters = { new JsonStringEnumConverter() } });
        Assert.Equal("Closed", TaggedFilter(strings, t => t.Plain == OrderStatus.Closed).Value!.GetValue<string>());
    }

    [Fact]
    public void AVectorArrayMemberIsComparedAsAVector()
    {
        var embedding = new[] { 1f, 0.5f };
        Assert.Equal("#vector(1,0.5)", TaggedFilter(_harness, t => t.Embedding == embedding).Value!.GetValue<string>());
        var weights = new[] { 2.0 };
        Assert.Equal("#vector(2)", TaggedFilter(_harness, t => t.Weights == weights).Value!.GetValue<string>());
    }

    [Fact]
    public void AGeoPointIsComparedAsAGeoValue()
    {
        var point = new GeoPoint(1.5, -2);
        Assert.Equal("#geo(1.5,-2)", _harness.Value(o => o.Location == point)!.GetValue<string>());
    }

    [Fact]
    public void DateTimeKindsFollowTheTemporalZoneRule()
    {
        var unspecified = new DateTime(2024, 3, 1, 8, 0, 0, DateTimeKind.Unspecified);
        Assert.Equal("#datetime(2024-03-01T08:00)", _harness.Value(o => o.CreatedAt == unspecified)!.GetValue<string>());
        var utc = new DateTime(2024, 3, 1, 8, 0, 0, DateTimeKind.Utc);
        Assert.Throws<MappingException>(() => _harness.Value(o => o.CreatedAt == utc));
        var madrid = new LinqHarness(TimeZoneInfo.FindSystemTimeZoneById("Europe/Madrid"));
        Assert.Equal("#datetime(2024-03-01T09:00)", madrid.Value(o => o.CreatedAt == utc)!.GetValue<string>());
        var offset = new DateTimeOffset(2024, 7, 1, 8, 0, 0, TimeSpan.Zero);
        Assert.Equal("#datetime(2024-07-01T10:00)", TaggedFilter(madrid, t => t.At == offset).Value!.GetValue<string>());
        Assert.Throws<MappingException>(() => TaggedFilter(_harness, t => t.At == offset));
    }

    [Fact]
    public void AnObjectOperandIsComparedAsAnObject()
    {
        var lima = new Address { City = "Lima" };
        Assert.Equal("{\"city\":\"Lima\"}", _harness.Value(o => o.Address == lima)!.ToJsonString());
    }

    [Fact]
    public void ALookAlikeStringIsRefused()
    {
        var error = Assert.Throws<MappingException>(() => _harness.Value(o => o.Name == "#geo(1,2)"));
        Assert.Contains("name", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void NumbersOutsideTheDoubleRangeAreRefused()
    {
        var huge = (double)long.MaxValue;
        Assert.Throws<MappingException>(() => _harness.Value(o => o.Total == huge));
    }
}

using System;
using System.Collections.Generic;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit;

public sealed class DocumentTests
{
    [Fact]
    public void ADocumentIsImmutableAndReadsTypedValues()
    {
        var document = Document.Of(
            "_id", "d-1",
            "name", "Ana",
            "total", 12,
            "paid", true,
            "at", new DateTime(2024, 1, 1),
            "time", new TimeOnly(9, 0),
            "point", new GeoPoint(1, 2),
            "vector", Vector.Of(1),
            "nested", Document.Of("city", "Lima"),
            "list", new object?[] { 1, "a", null },
            "missing", null,
            "stage", OrderStatus.Closed,
            "map", new Dictionary<string, object?> { ["k"] = 1 },
            "address", new Address { City = "Quito" });
        Assert.Equal("d-1", document.Id);
        Assert.Equal("Ana", document.GetString("name"));
        Assert.Equal(12, document.GetDouble("total"));
        Assert.Equal(12L, document.GetLong("total"));
        Assert.True(document.GetBoolean("paid"));
        Assert.Equal(new DateTime(2024, 1, 1), document.GetDateTime("at"));
        Assert.Equal(new TimeOnly(9, 0), document.GetTimeOnly("time"));
        Assert.Equal(new GeoPoint(1, 2), document.GetGeoPoint("point"));
        Assert.Equal(Vector.Of(1), document.GetVector("vector"));
        Assert.Equal("Lima", document.GetString("nested.city"));
        Assert.Equal("Lima", document.GetDocument("nested")!.GetString("city"));
        Assert.Equal([1.0, "a", null], document.GetList("list")!);
        Assert.True(document.Has("missing"));
        Assert.False(document.Has("absent"));
        Assert.Null(document.Get("missing"));
        Assert.Equal("Closed", document.GetString("stage"));
        Assert.Equal(1, document.GetDouble("map.k"));
        Assert.Equal("Quito", document.GetString("address.city"));
        Assert.Null(document.GetString("total"));
        Assert.Null(document.GetBoolean("name"));
        Assert.Null(document.GetDouble("nested.city.deeper"));
        var changed = document.With("name", "Bo").Without("paid");
        Assert.Equal("Ana", document.GetString("name"));
        Assert.Equal("Bo", changed.GetString("name"));
        Assert.False(changed.Has("paid"));
        Assert.Contains("name", document.FieldNames);
    }

    [Fact]
    public void DocumentsCompareByContent()
    {
        var parsed = Document.Parse("{\"a\":1,\"b\":[true]}");
        var built = Document.From([new KeyValuePair<string, object?>("a", 1), new KeyValuePair<string, object?>("b", new[] { true })]);
        Assert.Equal(parsed, built);
        Assert.Equal(parsed.GetHashCode(), Document.Parse(parsed.ToJson()).GetHashCode());
        Assert.Equal("{\"a\":1,\"b\":[true]}", parsed.ToString());
        Assert.NotEqual(parsed, Document.Empty);
        Assert.False(parsed.Equals(null));
    }

    [Fact]
    public void InvalidInputIsRefused()
    {
        Assert.Throws<ArgumentException>(() => Document.Of("a"));
        Assert.Throws<ArgumentException>(() => Document.Of(1, 2));
        Assert.Throws<MappingException>(() => Document.Parse("[1]"));
        Assert.Throws<MappingException>(() => Document.Parse("{"));
        Assert.Throws<MappingException>(() => Document.Of("a", "#geo(1,2)"));
        Assert.Throws<MappingException>(() => Document.Of("a", double.PositiveInfinity));
        Assert.Throws<MappingException>(() => Document.Of("a", 1L << 60));
        Assert.Throws<MappingException>(() => Document.Of("a", new DateTime(2024, 1, 1, 0, 0, 0, DateTimeKind.Utc)));
        Assert.Throws<MappingException>(() => Document.Of("a", DateTimeOffset.UnixEpoch));
        Assert.Throws<MappingException>(() => Document.Of("a", new Dictionary<int, int> { [1] = 1 }));
        Assert.Throws<MappingException>(() => Document.Of("a", new Unmappable()));
    }

    private sealed class Unmappable
    {
        public IntPtr Pointer { get; } = IntPtr.Zero;
    }
}

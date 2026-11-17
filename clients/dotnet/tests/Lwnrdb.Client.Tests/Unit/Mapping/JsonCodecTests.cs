using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Text.Json.Serialization;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Mapping;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit.Mapping;

public sealed class JsonCodecTests
{
    private static readonly TimeZoneInfo Madrid = TimeZoneInfo.FindSystemTimeZoneById("Europe/Madrid");

    private static JsonCodec<T> Codec<T>(TimeZoneInfo? zone = null, JsonSerializerOptions? json = null) =>
        new(LwnrdbJson.CreateOptions(json, zone));

    [Fact]
    public void IdIsAliasedToTheIdFieldBothWays()
    {
        var codec = Codec<Order>();
        var document = codec.Encode(new Order { Id = "o-1", Total = 2 });
        Assert.Equal("o-1", document.Id);
        Assert.False(document.Has("id"));
        Assert.Equal("o-1", codec.Decode(document).Id);
        Assert.False(codec.Encode(new Order()).Has("_id"));
    }

    [Fact]
    public void CustomValuesMapAtAnyDepth()
    {
        var codec = Codec<Holder>();
        var holder = new Holder
        {
            At = new DateTime(2024, 1, 1, 10, 0, 0),
            Time = new TimeOnly(9, 30),
            Point = new GeoPoint(1, 2),
            Vector = Vector.Of(1, 2),
            Nested = [new Holder { Point = new GeoPoint(3, 4) }],
            Floats = [0.5f],
        };
        var document = codec.Encode(holder);
        Assert.Equal("#datetime(2024-01-01T10:00)", document.Node["at"]!.GetValue<string>());
        Assert.Equal("#geo(3,4)", document.Node["nested"]![0]!["point"]!.GetValue<string>());
        Assert.Equal("#vector(0.5)", document.Node["floats"]!.GetValue<string>());
        var decoded = codec.Decode(document);
        Assert.Equal(holder.At, decoded.At);
        Assert.Equal(DateTimeKind.Unspecified, decoded.At.Kind);
        Assert.Equal(holder.Time, decoded.Time);
        Assert.Equal(holder.Vector, decoded.Vector);
        Assert.Equal(new GeoPoint(3, 4), decoded.Nested![0].Point);
        Assert.Equal([0.5f], decoded.Floats!);
    }

    [Fact]
    public void InstantsNeedATemporalZone()
    {
        var utc = new Holder { At = new DateTime(2024, 3, 31, 0, 30, 0, DateTimeKind.Utc) };
        var error = Assert.Throws<MappingException>(() => Codec<Holder>().Encode(utc));
        Assert.Contains("at", error.Message, StringComparison.Ordinal);
        Assert.Equal("#datetime(2024-03-31T01:30)", Codec<Holder>(Madrid).Encode(utc).Node["at"]!.GetValue<string>());
        var afterTheChange = new Holder { At = new DateTime(2024, 3, 31, 1, 30, 0, DateTimeKind.Utc) };
        Assert.Equal("#datetime(2024-03-31T03:30)", Codec<Holder>(Madrid).Encode(afterTheChange).Node["at"]!.GetValue<string>());
        var offset = new Holder { Offset = new DateTimeOffset(2024, 7, 1, 8, 0, 0, TimeSpan.Zero) };
        Assert.Throws<MappingException>(() => Codec<Holder>().Encode(offset));
        var zoned = Codec<Holder>(Madrid);
        var document = zoned.Encode(offset);
        Assert.Equal("#datetime(2024-07-01T10:00)", document.Node["offset"]!.GetValue<string>());
        Assert.Equal(offset.Offset, zoned.Decode(document).Offset);
        Assert.Throws<MappingException>(() => Codec<Holder>().Decode(document));
    }

    [Fact]
    public void ADateTimeInADstGapIsConvertedWithTheZone()
    {
        var holder = new Holder { Offset = new DateTimeOffset(2024, 3, 31, 1, 0, 0, TimeSpan.Zero) };
        Assert.Equal("#datetime(2024-03-31T03:00)", Codec<Holder>(Madrid).Encode(holder).Node["offset"]!.GetValue<string>());
        Assert.Equal("#datetime(0001-01-01T00:00)", Codec<Holder>().Encode(new Holder()).Node["at"]!.GetValue<string>());
    }

    [Fact]
    public void ALookAlikeStringIsRefusedWithItsPath()
    {
        var error = Assert.Throws<MappingException>(() => Codec<Holder>().Encode(new Holder { Label = "#time(10:00)" }));
        Assert.Contains("label", error.Message, StringComparison.OrdinalIgnoreCase);
        var plain = Codec<Holder>().Encode(new Holder { Tags = new Dictionary<string, string> { ["#geo(1,2)"] = "key" } });
        Assert.Equal("key", plain.Node["tags"]!["#geo(1,2)"]!.GetValue<string>());
    }

    [Fact]
    public void UnsafeNumbersAreRefusedWithTheirPath()
    {
        var error = Assert.Throws<MappingException>(() => Codec<Holder>().Encode(new Holder { Count = long.MaxValue }));
        Assert.Contains("count", error.Message, StringComparison.OrdinalIgnoreCase);
        var nan = Assert.Throws<MappingException>(() => Codec<Holder>().Encode(new Holder { Ratio = double.NaN }));
        Assert.Contains("ratio", nan.Message, StringComparison.OrdinalIgnoreCase);
        Assert.Throws<MappingException>(() => Codec<Holder>().Encode(new Holder { Money = 1e20m }));
        Assert.Equal(0.1, Codec<Holder>().Encode(new Holder { Money = 0.1m }).GetDouble("money"));
    }

    [Fact]
    public void AMalformedDocumentNamesItsId()
    {
        var document = Document.Of("_id", "x-1", "at", "not a date");
        var error = Assert.Throws<MappingException>(() => Codec<Holder>().Decode(document));
        Assert.Contains("x-1", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void AUserJsonSerializerContextIsUsedForTheUsersTypes()
    {
        var options = new JsonSerializerOptions { TypeInfoResolver = UserContext.Default };
        var codec = Codec<UserRecord>(json: options);
        var document = codec.Encode(new UserRecord { Id = "u", DisplayName = "Ana", At = new DateTime(2024, 1, 1) });
        Assert.Equal("Ana", document.GetString("DisplayName"));
        Assert.Equal("u", document.Id);
        Assert.Equal(new DateTime(2024, 1, 1), codec.Decode(document).At);
    }

    [Fact]
    public void ATypeThatIsNotAnObjectIsRefused()
    {
        Assert.Throws<ArgumentException>(() => Codec<string>());
        Assert.Throws<ArgumentException>(() => Codec<List<int>>());
    }

    public sealed class Holder
    {
        public string? Label { get; set; }

        public DateTime At { get; set; }

        public DateTimeOffset? Offset { get; set; }

        public TimeOnly Time { get; set; }

        public GeoPoint Point { get; set; }

        public Vector? Vector { get; set; }

        public List<Holder>? Nested { get; set; }

        [LwnrdbVector]
        public float[]? Floats { get; set; }

        public long Count { get; set; }

        public double Ratio { get; set; }

        public decimal Money { get; set; }

        public Dictionary<string, string>? Tags { get; set; }
    }

    public sealed class UserRecord
    {
        public string? Id { get; set; }

        public string? DisplayName { get; set; }

        public DateTime At { get; set; }
    }
}

[JsonSerializable(typeof(JsonCodecTests.UserRecord))]
internal sealed partial class UserContext : JsonSerializerContext;

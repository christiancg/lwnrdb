using System;
using System.Collections;
using System.Collections.Generic;
using System.Text.Json;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit;

public sealed class DocumentValuesTests
{
    [Fact]
    public void EveryNumericTypeEncodes()
    {
        object[] numbers = [(byte)1, (sbyte)-1, (short)2, (ushort)3, 4, 5u, 6L, 7ul, 8.5f, 9.5, 10.5m];
        foreach (var number in numbers)
        {
            Assert.Equal(JsonValueKind.Number, DocumentValues.Encode(number, "n")!.GetValueKind());
        }
        Assert.Throws<MappingException>(() => DocumentValues.Encode(ulong.MaxValue, "n"));
        Assert.Throws<MappingException>(() => DocumentValues.Encode(float.NaN, "n"));
    }

    [Fact]
    public void JsonTreesAndElementsAreCopiedAndChecked()
    {
        var node = JsonNode.Parse("{\"a\":[1,{\"b\":2}]}");
        var copy = DocumentValues.Encode(node, "x");
        Assert.True(JsonNode.DeepEquals(node, copy));
        Assert.NotSame(node, copy);
        using var parsed = JsonDocument.Parse("[1,2]");
        Assert.Equal("[1,2]", DocumentValues.Encode(parsed.RootElement, "x")!.ToJsonString());
        Assert.Throws<MappingException>(() => DocumentValues.Encode(JsonNode.Parse("{\"big\":12345678901234567890}"), "x"));
        Assert.Throws<MappingException>(() => DocumentValues.Encode(JsonNode.Parse("{\"big\":1e300}")!["big"]!.GetValue<double>() * 10, "x"));
    }

    [Fact]
    public void DictionariesAndSequencesEncodeTheirItems()
    {
        var hashtable = new Hashtable { ["k"] = 1 };
        Assert.Equal("{\"k\":1}", DocumentValues.Encode(hashtable, "x")!.ToJsonString());
        Assert.Throws<MappingException>(() => DocumentValues.Encode(new Hashtable { [1] = 1 }, "x"));
        Assert.Equal("[\"a\",null]", DocumentValues.Encode(new List<string?> { "a", null }, "x")!.ToJsonString());
        Assert.Throws<MappingException>(() => DocumentValues.Encode(new[] { "#geo(1,2)" }, "x"));
    }

    [Fact]
    public void AnObjectThatIsNotAJsonObjectIsRefusedAsADocument()
    {
        Assert.Throws<MappingException>(() => DocumentValues.EncodeObject(new[] { 1 }, null));
        Assert.Equal("{\"a\":1}", DocumentValues.EncodeObject(new Dictionary<string, int> { ["a"] = 1 }, null).ToJsonString());
    }

    [Fact]
    public void DecodingMapsEveryKind()
    {
        Assert.Null(DocumentValues.Decode(null));
        Assert.Equal(true, DocumentValues.Decode(JsonValue.Create(true)));
        Assert.Equal(false, DocumentValues.Decode(JsonValue.Create(false)));
        Assert.Equal("plain", DocumentValues.Decode(JsonValue.Create("plain")));
        Assert.IsType<List<object?>>(DocumentValues.Decode(new JsonArray(1)));
        Assert.IsType<Document>(DocumentValues.Decode(new JsonObject()));
        Assert.Equal(TimeSpan.FromHours(1).Hours, ((TimeOnly)DocumentValues.Decode(JsonValue.Create("#time(01:00)"))!).Hour);
    }
}

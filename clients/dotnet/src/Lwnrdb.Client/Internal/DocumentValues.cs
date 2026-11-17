using System;
using System.Collections;
using System.Globalization;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Mapping;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Internal;

internal static class DocumentValues
{
    public static JsonNode? Encode(object? value, string path, TimeZoneInfo? zone = null) => value switch
    {
        null => null,
        string text => JsonValue.Create(GuardedText(text, path)),
        bool flag => JsonValue.Create(flag),
        char character => JsonValue.Create(GuardedText(character.ToString(), path)),
        double number => JsonValue.Create(NumberGuard.Check(number, path)),
        float number => JsonValue.Create(NumberGuard.Check(number, path)),
        decimal number => Number(number, path),
        int or long or short or byte or sbyte or uint or ulong or ushort => Integer(value, path),
        DateTime dateTime => JsonValue.Create(CustomValues.Format(WallClock(dateTime, path, zone))),
        DateTimeOffset offset => JsonValue.Create(CustomValues.Format(WallClock(offset, path, zone))),
        TimeOnly time => JsonValue.Create(CustomValues.Format(time)),
        GeoPoint point => JsonValue.Create(CustomValues.Format(point)),
        Vector vector => JsonValue.Create(CustomValues.Format(vector)),
        Document document => document.Node.DeepClone(),
        JsonNode node => Checked(node.DeepClone(), path),
        JsonElement element => Checked(JsonSerializer.SerializeToNode(element), path),
        Enum member => JsonValue.Create(member.ToString()),
        IDictionary dictionary => EncodeDictionary(dictionary, path, zone),
        IEnumerable items => EncodeArray(items, path, zone),
        _ => Checked(Serialized(value, path), path),
    };

    public static JsonObject EncodeObject(object value, TimeZoneInfo? zone)
    {
        if (Encode(value, string.Empty, zone) is JsonObject encoded)
        {
            return encoded;
        }
        throw new MappingException($"a {value.GetType().Name} does not map to a JSON object");
    }

    public static object? Decode(JsonNode? node) => node switch
    {
        null => null,
        JsonObject obj => Document.Wrap(obj),
        JsonArray array => array.Select(Decode).ToList(),
        JsonValue value => DecodeValue(value),
        _ => null,
    };

    public static DateTime WallClock(DateTime value, string path, TimeZoneInfo? zone)
    {
        if (value.Kind == DateTimeKind.Unspecified)
        {
            return value;
        }
        if (zone is null)
        {
            throw new MappingException(
                $"{NumberGuard.Label(path)} is a DateTime of Kind {value.Kind}, an instant; set ClientSettings.TemporalZone or use Kind Unspecified, since the server's date-time has no zone");
        }
        return DateTime.SpecifyKind(TimeZoneInfo.ConvertTime(value, zone), DateTimeKind.Unspecified);
    }

    public static DateTime WallClock(DateTimeOffset value, string path, TimeZoneInfo? zone)
    {
        if (zone is null)
        {
            throw new MappingException(
                $"{NumberGuard.Label(path)} is a DateTimeOffset, an instant; set ClientSettings.TemporalZone or store a DateTime of Kind Unspecified, since the server's date-time has no zone");
        }
        return TimeZoneInfo.ConvertTime(value, zone).DateTime;
    }

    public static string GuardedText(string text, string path)
    {
        if (CustomValues.LooksCustom(text))
        {
            throw new MappingException(
                $"{NumberGuard.Label(path)} looks like a custom value ({text}); the server would read it as one, so it cannot be stored as text");
        }
        return text;
    }

    private static object? DecodeValue(JsonValue value) => value.GetValueKind() switch
    {
        JsonValueKind.String => DecodeText(value.GetValue<string>()),
        JsonValueKind.Number => JsonNumbers.ToDouble(value),
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        _ => null,
    };

    private static object DecodeText(string text) => CustomValues.LooksCustom(text) ? CustomValues.Parse(text) : text;

    private static JsonValue Number(decimal number, string path)
    {
        NumberGuard.CheckTree(JsonValue.Create(number), path);
        return JsonValue.Create(number);
    }

    private static JsonValue Integer(object value, string path)
    {
        var node = JsonValue.Create(Convert.ToDecimal(value, CultureInfo.InvariantCulture));
        NumberGuard.CheckTree(node, path);
        return node;
    }

    private static JsonObject EncodeDictionary(IDictionary dictionary, string path, TimeZoneInfo? zone)
    {
        var encoded = new JsonObject();
        foreach (DictionaryEntry entry in dictionary)
        {
            var key = entry.Key as string
                ?? throw new MappingException($"{NumberGuard.Label(path)} has a key that is not a string: {entry.Key}");
            encoded[key] = Encode(entry.Value, Child(path, key), zone);
        }
        return encoded;
    }

    private static JsonArray EncodeArray(IEnumerable items, string path, TimeZoneInfo? zone)
    {
        var encoded = new JsonArray();
        var index = 0;
        foreach (var item in items)
        {
            encoded.Add(Encode(item, path + "[" + index.ToString(CultureInfo.InvariantCulture) + "]", zone));
            index++;
        }
        return encoded;
    }

    private static JsonNode? Serialized(object value, string path)
    {
        try
        {
            return JsonSerializer.SerializeToNode(value, value.GetType(), LwnrdbJson.Default);
        }
        catch (JsonException error)
        {
            throw new MappingException($"{NumberGuard.Label(path)} does not map to JSON: {error.Message}", error);
        }
        catch (NotSupportedException error)
        {
            throw new MappingException($"{NumberGuard.Label(path)} does not map to JSON: {error.Message}", error);
        }
    }

    private static JsonNode? Checked(JsonNode? node, string path)
    {
        NumberGuard.CheckTree(node, path);
        return node;
    }

    private static string Child(string path, string key) => path.Length == 0 ? key : path + "." + key;
}

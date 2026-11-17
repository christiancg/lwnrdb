using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Internal;

namespace Lwnrdb.Client.Tests.Support;

public static class JsonTrees
{
    public static bool Equivalent(JsonNode? left, JsonNode? right) => (left, right) switch
    {
        (null, null) => true,
        (JsonObject a, JsonObject b) => a.Count == b.Count && a.All(entry => b.ContainsKey(entry.Key) && Equivalent(entry.Value, b[entry.Key])),
        (JsonArray a, JsonArray b) => a.Count == b.Count && a.Zip(b).All(pair => Equivalent(pair.First, pair.Second)),
        (JsonValue a, JsonValue b) => SameValue(a, b),
        _ => false,
    };

    private static bool SameValue(JsonValue left, JsonValue right)
    {
        var kind = left.GetValueKind();
        if (kind != right.GetValueKind())
        {
            return false;
        }
        if (kind == JsonValueKind.Number)
        {
            return JsonNumbers.ToDouble(left).Equals(JsonNumbers.ToDouble(right));
        }
        if (kind != JsonValueKind.String)
        {
            return JsonNode.DeepEquals(left, right);
        }
        var a = left.GetValue<string>();
        var b = right.GetValue<string>();
        return a == b || (CustomValues.LooksCustom(a) && CustomValues.LooksCustom(b) && Equals(CustomValues.Parse(a), CustomValues.Parse(b)));
    }
}

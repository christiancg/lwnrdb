using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Lwnrdb.Client.Internal;

internal static class JsonNumbers
{
    public static double? Of(JsonNode? node) =>
        node is JsonValue value && value.GetValueKind() == JsonValueKind.Number ? ToDouble(value) : null;

    public static double ToDouble(JsonValue value) =>
        value.TryGetValue<double>(out var number)
            ? number
            : double.Parse(value.ToJsonString(), NumberStyles.Float, CultureInfo.InvariantCulture);
}

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Internal;

internal static class ResponseDecoder
{
    public static string? TextOf(JsonNode? node) => node switch
    {
        null => null,
        JsonValue value when value.GetValueKind() == JsonValueKind.String => value.GetValue<string>(),
        _ => node.ToJsonString(),
    };

    public static bool BooleanOf(JsonNode? node, bool fallback) =>
        node is JsonValue value && value.GetValueKind() is JsonValueKind.True or JsonValueKind.False
            ? value.GetValue<bool>()
            : fallback;

    public static double? NumberOf(JsonNode? node) => JsonNumbers.Of(node);

    public static long? WholeOf(JsonNode? node) => NumberOf(node) is { } number ? (long)number : null;

    public static JsonObject Check(JsonObject frame)
    {
        var status = WireNames.StatusOf(TextOf(frame["status"]));
        if (status is Status.Error or Status.NotFound or Status.Unauthenticated or Status.Forbidden)
        {
            throw Raise(frame);
        }
        return frame;
    }

    public static LwnrdbException Raise(JsonObject frame)
    {
        var code = TextOf(frame["errorCode"]);
        var status = WireNames.StatusOf(TextOf(frame["status"]));
        var message = TextOf(frame["message"]) ?? ErrorCode.Of(code)?.Message ?? "request failed";
        var text = code is null ? message : $"{message} ({code})";
        var operation = TextOf(frame["type"]);
        return (code is "500-33" or "500-34" ? "UNKNOWN" : code?.Split('-')[0]) switch
        {
            "UNKNOWN" => new UnknownOutcomeException(text, code, status, operation),
            "401" => new AuthenticationException(text, code, status, operation),
            "403" => new ForbiddenException(text, code, status, operation),
            "404" => new NotFoundException(text, code, status, operation),
            "409" => new ConflictException(text, code, status, operation),
            "410" => new ListenEndedException(text, code, status, operation),
            "503" => new UnavailableException(text, code, status, operation),
            _ => status switch
            {
                Status.Unauthenticated => new AuthenticationException(text, code, status, operation),
                Status.Forbidden => new ForbiddenException(text, code, status, operation),
                Status.NotFound => new NotFoundException(text, code, status, operation),
                _ => new LwnrdbException(text, code, status, operation),
            },
        };
    }

    public static IReadOnlyList<Document> Documents(JsonObject frame, string field) =>
        frame[field] is JsonArray array ? [.. array.OfType<JsonObject>().Select(Document.Wrap)] : [];

    public static Document? DocumentOf(JsonObject frame, string field) =>
        frame[field] is JsonObject obj ? Document.Wrap(obj) : null;

    public static IReadOnlyList<string> Strings(JsonNode? node) =>
        node is JsonArray array ? [.. array.Select(item => TextOf(item) ?? "null")] : [];

    public static bool IsEmptyAnswer(Exception error, params string[] codes) =>
        error is NotFoundException notFound && codes.Any(notFound.HasCode);
}

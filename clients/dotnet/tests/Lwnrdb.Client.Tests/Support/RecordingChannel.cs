using System;
using System.Collections.Generic;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Mapping;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Tests.Support;

internal sealed class RecordingChannel : IRequestChannel
{
    public List<JsonObject> Requests { get; } = [];

    public List<Operation> Operations { get; } = [];

    public Func<JsonObject, JsonObject> Replies { get; set; } = request => Ok(request);

    public JsonObject Last => Requests.Count > 0 ? Requests[^1] : throw new InvalidOperationException("no request was sent");

    public static JsonObject Ok(JsonObject request, JsonObject? extra = null)
    {
        var reply = new JsonObject { ["type"] = request["type"]?.DeepClone(), ["status"] = "OK", ["message"] = "Ok" };
        foreach (var (key, value) in extra ?? [])
        {
            reply[key] = value?.DeepClone();
        }
        return reply;
    }

    public static JsonObject Error(JsonObject request, string status, string code, string message = "refused") => new()
    {
        ["type"] = request["type"]?.DeepClone(),
        ["status"] = status,
        ["message"] = message,
        ["errorCode"] = code,
    };

    public Task<JsonObject> SendAsync(Operation operation, JsonObject request, CancellationToken ct)
    {
        Requests.Add((JsonObject)request.DeepClone());
        Operations.Add(operation);
        return Task.FromResult(ResponseDecoder.Check(Replies(request)));
    }

    public static ClientContext Context(IRequestChannel channel, TimeZoneInfo? zone = null, System.Text.Json.JsonSerializerOptions? json = null)
    {
        var encoder = new RequestEncoder(zone);
        return new ClientContext(
            channel,
            encoder,
            new ScriptRequestEncoder(encoder),
            null,
            "alice",
            TimeSpan.FromSeconds(5),
            LwnrdbJson.CreateOptions(json, zone));
    }
}

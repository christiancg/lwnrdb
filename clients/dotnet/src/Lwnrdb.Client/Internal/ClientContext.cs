using System;
using System.Text.Json;

namespace Lwnrdb.Client.Internal;

internal sealed record ClientContext(
    IRequestChannel Channel,
    RequestEncoder Encoder,
    ScriptRequestEncoder Scripts,
    Listens? Listens,
    string? Username,
    TimeSpan RequestTimeout,
    JsonSerializerOptions Json)
{
    public TimeZoneInfo? TemporalZone => Encoder.TemporalZone;

    public ClientContext WithChannel(IRequestChannel channel) => this with { Channel = channel, Listens = null };
}

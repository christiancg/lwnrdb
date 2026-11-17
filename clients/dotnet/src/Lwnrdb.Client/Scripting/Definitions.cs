using System;
using System.Collections.Generic;

namespace Lwnrdb.Client.Scripting;

public sealed record ProcedureDefinition(string Name, string Script)
{
    public string? Description { get; init; }

    public bool? Enabled { get; init; }

    public long? IfVersion { get; init; }
}

public sealed record TriggerDefinition(string Name, string ProcedureName, IReadOnlyList<TriggerEvent> Events)
{
    public TriggerTiming? Timing { get; init; }

    public TriggerMode? Mode { get; init; }

    public bool? AllowCascade { get; init; }

    public bool? Enabled { get; init; }

    public long? IfVersion { get; init; }
}

public sealed record ScheduleDefinition(string Name, string ProcedureName)
{
    public string? Cron { get; init; }

    public TimeSpan? Interval { get; init; }

    public Document? Args { get; init; }

    public TimeSpan? Timeout { get; init; }

    public string? Description { get; init; }

    public bool? Enabled { get; init; }

    public long? IfVersion { get; init; }
}

public sealed record ProcedureInfo(string Name, long Version, bool Enabled, string? Description, string? Source, Document Raw);

public sealed record TriggerInfo(
    string Name,
    string? Collection,
    IReadOnlyList<string> Events,
    string ProcedureName,
    string Timing,
    string Mode,
    bool Enabled,
    long Version,
    string? Definer,
    Document Raw);

public sealed record ScheduleInfo(
    string Name,
    string ProcedureName,
    string? Cron,
    TimeSpan? Interval,
    bool Enabled,
    long Version,
    string? Definer,
    DateTimeOffset? NextRunAt,
    Document Raw);

public sealed record TriggerSaved(long Version, string Definer);

public sealed record TriggerTestResult(string Decision, Document? Document, IReadOnlyList<string> Logs, bool LogsTruncated);

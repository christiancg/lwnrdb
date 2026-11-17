using System.Collections.Generic;

namespace Lwnrdb.Client.Admin;

public sealed record UserInfo(
    string Username,
    bool IsAdmin,
    IReadOnlyList<string> GlobalPermissions,
    Document DatabasePermissions,
    Document CollectionPermissions,
    Document ScriptPermissions,
    IReadOnlyList<string> OwnedDatabases,
    Document Raw);

public sealed record TransactionInfo(
    string DtxId,
    string? Coordinator,
    bool CoordinatorReachable,
    IReadOnlyList<string> Participants,
    long AgeMs,
    Document Raw);

public sealed record ScriptRun(
    string RunId,
    string? Node,
    string Kind,
    string? Database,
    string? Name,
    string? Username,
    long AgeMs,
    Document Raw);

public sealed record TriggerRun(
    string RunId,
    string Status,
    string? Database,
    string? Collection,
    string? Trigger,
    string? Procedure,
    string? Event,
    long Attempts,
    string? LastError,
    Document Raw);

public sealed record ServerInfo(string? State, string? Version, Document Raw);

using System.Collections.Generic;

namespace Lwnrdb.Client.Admin;

public sealed record Permissions
{
    public bool Admin { get; init; }

    public IReadOnlyList<GlobalPermission> Global { get; init; } = [];

    public IReadOnlyDictionary<string, DataPermission> Databases { get; init; } = new Dictionary<string, DataPermission>();

    public IReadOnlyDictionary<string, DataPermission> Collections { get; init; } = new Dictionary<string, DataPermission>();

    public IReadOnlyDictionary<string, ScriptPermission> Scripts { get; init; } = new Dictionary<string, ScriptPermission>();

    public static string CollectionKey(string database, string collection) => database + "|" + collection;
}

using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Mapping;
using Lwnrdb.Client.Protocol;
using Lwnrdb.Client.Scripting;

namespace Lwnrdb.Client;

public sealed class Database
{
    private readonly ClientContext _context;

    internal Database(ClientContext context, string name)
    {
        _context = context;
        Name = Names.Database(name);
    }

    public string Name { get; }

    public async Task CreateAsync(CancellationToken ct = default) =>
        await SendAsync(Operation.CreateDatabase, RequestEncoder.Request("CREATE_DATABASE", Name), ct).ConfigureAwait(false);

    public async Task DropAsync(CancellationToken ct = default) =>
        await SendAsync(Operation.DropDatabase, RequestEncoder.Request("DROP_DATABASE", Name), ct).ConfigureAwait(false);

    public async Task<IReadOnlyList<string>> ListCollectionsAsync(CancellationToken ct = default)
    {
        var reply = await SendAsync(Operation.ListCollections, RequestEncoder.Request("LIST_COLLECTIONS", Name), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.Strings(reply["collections"]);
    }

    public Collection<T> Collection<T>(string name, IDocumentCodec<T>? codec = null) =>
        new(new CollectionOps<T>(_context, Name, name, codec ?? new JsonCodec<T>(_context.Json)));

    public Collection<Document> Collection(string name) =>
        new(new CollectionOps<Document>(_context, Name, name, IdentityCodec.Instance));

    public async Task<Document> StatsAsync(CancellationToken ct = default)
    {
        var reply = await SendAsync(Operation.GetDatabaseStats, RequestEncoder.Request("GET_DATABASE_STATS", Name), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.DocumentOf(reply, "stats") ?? Document.Empty;
    }

    public async Task SetOwnersAsync(IEnumerable<string> owners, CancellationToken ct = default) =>
        await SendAsync(Operation.SetDatabaseOwners, RequestEncoder.SetDatabaseOwners(Name, owners.ToList()), ct)
            .ConfigureAwait(false);

    public async Task<ScriptResult> RunScriptAsync(string script, Document? args = null, CancellationToken ct = default) =>
        Descriptors.ScriptResult(await SendAsync(Operation.RunScript, ScriptRequestEncoder.RunScript(Name, script, args), ct)
            .ConfigureAwait(false));

    public Procedures Procedures() => new(_context, Name);

    public Schedules Schedules() => new(_context, Name);

    public async Task<IReadOnlyList<TriggerInfo>> ListTriggersAsync(CancellationToken ct = default)
    {
        var reply = await SendAsync(Operation.ListTriggers, ScriptRequestEncoder.ListTriggers(Name, null), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "triggers").Select(Descriptors.Trigger)];
    }

    private Task<System.Text.Json.Nodes.JsonObject> SendAsync(
        Operation operation,
        System.Text.Json.Nodes.JsonObject request,
        CancellationToken ct) =>
        _context.Channel.SendAsync(operation, request, ct);
}

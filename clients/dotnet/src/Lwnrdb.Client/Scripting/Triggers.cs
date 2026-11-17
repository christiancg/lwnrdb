using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Scripting;

public sealed class Triggers
{
    private readonly ClientContext _context;
    private readonly string _database;
    private readonly string _collection;

    internal Triggers(ClientContext context, string database, string collection)
    {
        _context = context;
        _database = database;
        _collection = collection;
    }

    public async Task<TriggerSaved> SaveAsync(TriggerDefinition definition, CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.SaveTrigger, ScriptRequestEncoder.SaveTrigger(_database, _collection, definition), ct)
            .ConfigureAwait(false);
        return new TriggerSaved(ResponseDecoder.WholeOf(reply["version"]) ?? 0, ResponseDecoder.TextOf(reply["definer"]) ?? string.Empty);
    }

    public async Task DeleteAsync(string name, CancellationToken ct = default) =>
        await _context.Channel
            .SendAsync(Operation.DeleteTrigger, ScriptRequestEncoder.DeleteTrigger(_database, _collection, name), ct)
            .ConfigureAwait(false);

    public async Task<IReadOnlyList<TriggerInfo>> ListAsync(CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.ListTriggers, ScriptRequestEncoder.ListTriggers(_database, _collection), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "triggers").Select(Descriptors.Trigger)];
    }

    public async Task<TriggerTestResult> TestAsync(string name, TriggerEvent triggerEvent, Document sample, CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.TestTrigger, ScriptRequestEncoder.TestTrigger(_database, _collection, name, triggerEvent, sample), ct)
            .ConfigureAwait(false);
        return new TriggerTestResult(
            ResponseDecoder.TextOf(reply["decision"]) ?? string.Empty,
            ResponseDecoder.DocumentOf(reply, "document"),
            ResponseDecoder.Strings(reply["logs"]),
            ResponseDecoder.BooleanOf(reply["logsTruncated"], false));
    }
}

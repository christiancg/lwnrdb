using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Scripting;

public sealed class Procedures
{
    private readonly ClientContext _context;
    private readonly string _database;

    internal Procedures(ClientContext context, string database)
    {
        _context = context;
        _database = database;
    }

    public async Task<long> SaveAsync(ProcedureDefinition definition, CancellationToken ct = default)
    {
        var reply = await _context.Channel.SendAsync(Operation.SaveProcedure, ScriptRequestEncoder.SaveProcedure(_database, definition), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.WholeOf(reply["version"]) ?? 0;
    }

    public async Task DeleteAsync(string name, CancellationToken ct = default) =>
        await _context.Channel.SendAsync(Operation.DeleteProcedure, ScriptRequestEncoder.DeleteProcedure(_database, name), ct)
            .ConfigureAwait(false);

    public async Task<IReadOnlyList<ProcedureInfo>> ListAsync(bool? includeSource = null, CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.ListProcedures, ScriptRequestEncoder.ListProcedures(_database, includeSource), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "procedures").Select(Descriptors.Procedure)];
    }

    public async Task<ScriptResult> CallAsync(string name, Document? args = null, CancellationToken ct = default) =>
        Descriptors.ScriptResult(await _context.Channel
            .SendAsync(Operation.CallProcedure, ScriptRequestEncoder.CallProcedure(_database, name, args), ct)
            .ConfigureAwait(false));
}

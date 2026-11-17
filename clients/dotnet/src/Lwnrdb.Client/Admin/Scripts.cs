using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Admin;

public sealed class Scripts
{
    private readonly ClientContext _context;

    internal Scripts(ClientContext context)
    {
        _context = context;
    }

    public async Task<IReadOnlyList<ScriptRun>> ListAsync(CancellationToken ct = default)
    {
        var reply = await _context.Channel.SendAsync(Operation.ListScripts, RequestEncoder.Request("LIST_SCRIPTS"), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "scripts").Select(Descriptors.ScriptRun)];
    }

    public async Task<bool> CancelAsync(string runId, CancellationToken ct = default)
    {
        var reply = await _context.Channel.SendAsync(Operation.CancelScript, ScriptRequestEncoder.CancelScript(runId), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.BooleanOf(reply["cancelled"], false);
    }
}

using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Admin;

public sealed class TriggerRuns
{
    private readonly ClientContext _context;

    internal TriggerRuns(ClientContext context)
    {
        _context = context;
    }

    public async Task<IReadOnlyList<TriggerRun>> ListAsync(TriggerRunStatus? status = null, CancellationToken ct = default)
    {
        var reply = await _context.Channel.SendAsync(Operation.ListTriggerRuns, ScriptRequestEncoder.ListTriggerRuns(status), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "runs").Select(Descriptors.TriggerRun)];
    }

    public async Task<bool> ResolveAsync(string runId, TriggerRunDecision decision, CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.ResolveTriggerRun, ScriptRequestEncoder.ResolveTriggerRun(runId, decision), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.BooleanOf(reply["resolved"], false);
    }
}

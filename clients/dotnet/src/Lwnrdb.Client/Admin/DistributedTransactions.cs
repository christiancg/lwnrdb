using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Admin;

public sealed class DistributedTransactions
{
    private readonly ClientContext _context;

    internal DistributedTransactions(ClientContext context)
    {
        _context = context;
    }

    public async Task<IReadOnlyList<TransactionInfo>> ListAsync(CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.ListTransactions, RequestEncoder.Request("LIST_TRANSACTIONS"), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "transactions").Select(Descriptors.Transaction)];
    }

    public async Task ResolveAsync(string dtxId, TransactionDecision decision, CancellationToken ct = default) =>
        await _context.Channel
            .SendAsync(Operation.ResolveTransaction, ScriptRequestEncoder.ResolveTransaction(dtxId, decision), ct)
            .ConfigureAwait(false);
}

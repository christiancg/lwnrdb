using Lwnrdb.Client.Internal;

namespace Lwnrdb.Client.Admin;

public sealed class Admin
{
    private readonly ClientContext _context;

    internal Admin(ClientContext context)
    {
        _context = context;
    }

    public Users Users() => new(_context);

    public DistributedTransactions Transactions() => new(_context);

    public Scripts Scripts() => new(_context);

    public TriggerRuns TriggerRuns() => new(_context);
}

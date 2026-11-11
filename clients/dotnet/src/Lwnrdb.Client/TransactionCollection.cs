using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Query;

namespace Lwnrdb.Client;

[SuppressMessage("Naming", "CA1711")]
public sealed class TransactionCollection<T>
{
    internal TransactionCollection(CollectionOps<T> ops)
    {
        Ops = ops;
    }

    public string DatabaseName => Ops.Database;

    public string Name => Ops.Collection;

    internal CollectionOps<T> Ops { get; }

    public Task<SaveResult> SaveAsync(T value, CancellationToken ct = default) => Ops.SaveAsync(value, ct);

    public Task<BulkSaveResult> SaveAllAsync(IEnumerable<T> values, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(values);
        return Ops.SaveAllAsync(values, ct);
    }

    public Task<T?> FindByIdAsync(string id, CancellationToken ct = default) => Ops.FindByIdAsync(id, false, ct);

    public Task DeleteAsync(string id, CancellationToken ct = default) => Ops.DeleteAsync(id, ct);

    public Query<T> Query() => new(Ops, [], false);

    public IQueryable<T> AsQueryable() => LwnrdbQueryable<T>.Over(Ops);
}

using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.Linq;
using System.Linq.Expressions;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Protocol;
using Lwnrdb.Client.Query;
using Lwnrdb.Client.Scripting;

namespace Lwnrdb.Client;

[SuppressMessage("Naming", "CA1711")]
public sealed class Collection<T>
{
    internal Collection(CollectionOps<T> ops)
    {
        Ops = ops;
    }

    public string DatabaseName => Ops.Database;

    public string Name => Ops.Collection;

    internal CollectionOps<T> Ops { get; }

    public async Task CreateAsync(CancellationToken ct = default) =>
        await Ops.SendAsync(Operation.CreateCollection, RequestEncoder.Request("CREATE_COLLECTION", Ops.Database, Ops.Collection), ct)
            .ConfigureAwait(false);

    public async Task DropAsync(CancellationToken ct = default) =>
        await Ops.SendAsync(Operation.DropCollection, RequestEncoder.Request("DROP_COLLECTION", Ops.Database, Ops.Collection), ct)
            .ConfigureAwait(false);

    public Task<SaveResult> SaveAsync(T value, CancellationToken ct = default) => Ops.SaveAsync(value, ct);

    public Task<BulkSaveResult> SaveAllAsync(IEnumerable<T> values, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(values);
        return Ops.SaveAllAsync(values, ct);
    }

    public Task<T?> FindByIdAsync(string id, CancellationToken ct = default) => Ops.FindByIdAsync(id, false, ct);

    public Task<T?> FindByIdAsync(string id, bool dirtyRead, CancellationToken ct = default) => Ops.FindByIdAsync(id, dirtyRead, ct);

    public Task DeleteAsync(string id, CancellationToken ct = default) => Ops.DeleteAsync(id, ct);

    public Task<long> DeleteWhereAsync(Filter condition, CancellationToken ct = default) => Ops.DeleteWhereAsync(condition, ct);

    public Task<long> DeleteWhereAsync(Expression<Func<T, bool>> predicate, CancellationToken ct = default) =>
        Ops.DeleteWhereAsync(predicate, ct);

    public Query<T> Query() => new(Ops, [], false);

    public IQueryable<T> AsQueryable() => LwnrdbQueryable<T>.Over(Ops);

    public Indexes Indexes() => new(Ops.Context, Ops.Database, Ops.Collection);

    public Schema Schema() => new(Ops.Context, Ops.Database, Ops.Collection);

    public Triggers Triggers() => new(Ops.Context, Ops.Database, Ops.Collection);

    public Task<Subscription> ListenAsync(ListenHandler<T> handler, CancellationToken ct = default) =>
        ListenAsync(Query(), handler, ct);

    public Task<Subscription> ListenAsync(Query<T> query, ListenHandler<T> handler, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(query);
        ArgumentNullException.ThrowIfNull(handler);
        var listens = Ops.Context.Listens ?? throw new InvalidOperationException("listens are not available here");
        return listens.ListenAsync(Ops.Database, Ops.Collection, query.Steps, Ops.DecodeRow, handler, ct);
    }

    public Task<Subscription> ListenAsync(IQueryable<T> query, ListenHandler<T> handler, CancellationToken ct = default) =>
        ListenAsync(query.ToQuery(), handler, ct);
}

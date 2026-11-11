using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;

namespace Lwnrdb.Client.Query;

public sealed class Query<T> : IEquatable<Query<T>>
{
    private const string CountField = "count";

    internal Query(IQueryTarget<T> target, ImmutableArray<PipelineStep> steps, bool dirtyRead)
    {
        Target = target;
        Steps = steps;
        IsDirtyRead = dirtyRead;
    }

    public ImmutableArray<PipelineStep> Steps { get; }

    public bool IsDirtyRead { get; }

    internal IQueryTarget<T> Target { get; }

    public Query<T> Filter(Filter filter) => Then(new FilterStep(Checks.NotNull(filter, nameof(filter))));

    public Query<T> Map(params MapOperation[] operations)
    {
        ArgumentNullException.ThrowIfNull(operations);
        if (operations.Length == 0)
        {
            throw new ArgumentException("Map needs at least one operation", nameof(operations));
        }
        return Then(new MapStep(ImmutableArray.Create(operations)));
    }

    public Query<T> GroupBy(string field) => Then(new GroupByStep(Checks.Text(field, nameof(field))));

    public Query<T> Join(string collection, string localField, string remoteField, string asField) =>
        Then(new JoinStep(
            Checks.Text(collection, nameof(collection)),
            Checks.Text(localField, nameof(localField)),
            Checks.Text(remoteField, nameof(remoteField)),
            Checks.Text(asField, nameof(asField))));

    public Query<T> Distinct(string? field = null) =>
        Then(new DistinctStep(field is null ? null : Checks.Text(field, nameof(field))));

    public Query<T> Skip(int skip)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(skip);
        return Then(new SkipStep(skip));
    }

    public Query<T> Limit(int limit)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(limit);
        return Then(new LimitStep(limit));
    }

    public Query<T> Sort(string field, Order order = Order.Asc)
    {
        if (!Enum.IsDefined(order))
        {
            throw new ArgumentOutOfRangeException(nameof(order), order, "order must be Order.Asc or Order.Desc");
        }
        return Then(new SortStep(Checks.Text(field, nameof(field)), order == Order.Asc));
    }

    public Query<T> Reduce(string script) => Then(new ReduceStep(Checks.Text(script, nameof(script)), false, null, null));

    public Query<T> Reduce(string script, object? initialValue, string? resultField = null) =>
        Then(new ReduceStep(
            Checks.Text(script, nameof(script)),
            true,
            DocumentValues.Encode(initialValue, "initialValue", Target.Context.TemporalZone),
            resultField is null ? null : Checks.Text(resultField, nameof(resultField))));

    public Query<T> DirtyRead() => new(Target, Steps, true);

    public async Task<List<T>> ToListAsync(CancellationToken ct = default)
    {
        var rows = await ToDocumentsAsync(ct).ConfigureAwait(false);
        return [.. rows.Select(Target.DecodeRow)];
    }

    public Task<IReadOnlyList<Document>> ToDocumentsAsync(CancellationToken ct = default) =>
        Target.AggregateAsync(Steps, IsDirtyRead, ct);

    public async Task<T?> FirstOrDefaultAsync(CancellationToken ct = default)
    {
        var rows = await Limit(1).ToDocumentsAsync(ct).ConfigureAwait(false);
        return rows.Count == 0 ? default : Target.DecodeRow(rows[0]);
    }

    public async Task<long> CountAsync(CancellationToken ct = default)
    {
        var rows = await Then(new CountStep()).ToDocumentsAsync(ct).ConfigureAwait(false);
        return rows.Count == 0 ? 0 : rows[0].GetLong(CountField) ?? 0;
    }

    public Task<AnalyzeResult> AnalyzeAsync(CancellationToken ct = default) => Target.AnalyzeAsync(Steps, IsDirtyRead, ct);

    public bool Equals(Query<T>? other) =>
        other is not null && IsDirtyRead == other.IsDirtyRead && Steps.SequenceEqual(other.Steps);

    public override bool Equals(object? obj) => Equals(obj as Query<T>);

    public override int GetHashCode() => HashCode.Combine(Steps.Length, IsDirtyRead);

    public override string ToString() =>
        "Query[" + string.Join(", ", Steps) + (IsDirtyRead ? ", dirtyRead" : string.Empty) + "]";

    internal Query<T> Then(PipelineStep step) => new(Target, Steps.Add(step), IsDirtyRead);
}

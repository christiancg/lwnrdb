using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Query;

namespace Lwnrdb.Client.Internal.Linq;

internal interface ILwnrdbQueryable
{
    LwnrdbQueryProvider Provider { get; }

    bool IsRoot { get; }
}

internal interface IQueryRoot
{
    ClientContext Context { get; }

    string Database { get; }

    string Collection { get; }

    Type EntityType { get; }

    object? Decode(Document row);

    object ToQuery(ImmutableArray<PipelineStep> steps, bool dirtyRead);

    Task<IReadOnlyList<Document>> AggregateAsync(ImmutableArray<PipelineStep> steps, bool dirtyRead, CancellationToken ct);

    Task<AnalyzeResult> AnalyzeAsync(ImmutableArray<PipelineStep> steps, bool dirtyRead, CancellationToken ct);
}

internal sealed class QueryRoot<T>(IQueryTarget<T> target) : IQueryRoot
{
    public ClientContext Context => target.Context;

    public string Database => target.Database;

    public string Collection => target.Collection;

    public Type EntityType => typeof(T);

    public object? Decode(Document row) => target.DecodeRow(row);

    public object ToQuery(ImmutableArray<PipelineStep> steps, bool dirtyRead) => new Query<T>(target, steps, dirtyRead);

    public Task<IReadOnlyList<Document>> AggregateAsync(ImmutableArray<PipelineStep> steps, bool dirtyRead, CancellationToken ct) =>
        target.AggregateAsync(steps, dirtyRead, ct);

    public Task<AnalyzeResult> AnalyzeAsync(ImmutableArray<PipelineStep> steps, bool dirtyRead, CancellationToken ct) =>
        target.AnalyzeAsync(steps, dirtyRead, ct);
}

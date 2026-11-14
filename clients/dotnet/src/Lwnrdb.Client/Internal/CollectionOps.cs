using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Linq.Expressions;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal.Linq;
using Lwnrdb.Client.Protocol;
using Lwnrdb.Client.Query;

namespace Lwnrdb.Client.Internal;

internal sealed class CollectionOps<T> : IQueryTarget<T>
{
    public CollectionOps(ClientContext context, string database, string collection, IDocumentCodec<T> codec)
    {
        Context = context;
        Database = Names.Database(database);
        Collection = Names.Collection(collection);
        Codec = codec;
    }

    public ClientContext Context { get; }

    public string Database { get; }

    public string Collection { get; }

    public IDocumentCodec<T> Codec { get; }

    public T DecodeRow(Document row) => Codec.Decode(row);

    public async Task<SaveResult> SaveAsync(T value, CancellationToken ct)
    {
        var reply = await SendAsync(Operation.Save, RequestEncoder.Save(Database, Collection, Encode(value)), ct)
            .ConfigureAwait(false);
        return new SaveResult(ResponseDecoder.TextOf(reply["_id"]), ResponseDecoder.BooleanOf(reply["inserted"], false));
    }

    public async Task<BulkSaveResult> SaveAllAsync(IEnumerable<T> values, CancellationToken ct)
    {
        var encoded = values.Select(Encode).ToList();
        var reply = await SendAsync(Operation.BulkSave, RequestEncoder.BulkSave(Database, Collection, encoded), ct)
            .ConfigureAwait(false);
        return new BulkSaveResult(ResponseDecoder.Strings(reply["inserted"]), ResponseDecoder.Strings(reply["updated"]));
    }

    public async Task<T?> FindByIdAsync(string id, bool dirtyRead, CancellationToken ct)
    {
        var request = RequestEncoder.FindById(Database, Collection, Names.DocumentId(id), dirtyRead);
        try
        {
            var found = ResponseDecoder.DocumentOf(await SendAsync(Operation.FindById, request, ct).ConfigureAwait(false), "object");
            return found is null ? default : Codec.Decode(found);
        }
        catch (NotFoundException error) when (error.HasCode(ErrorCode.EntryNotFound))
        {
            return default;
        }
    }

    public async Task DeleteAsync(string id, CancellationToken ct) =>
        await SendAsync(Operation.Delete, RequestEncoder.Delete(Database, Collection, Names.DocumentId(id)), ct)
            .ConfigureAwait(false);

    public async Task<long> DeleteWhereAsync(Filter condition, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(condition);
        var reply = await SendAsync(Operation.Delete, Context.Encoder.DeleteWhere(Database, Collection, condition), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.WholeOf(reply["deletedCount"]) ?? 0;
    }

    public Task<long> DeleteWhereAsync(Expression<Func<T, bool>> predicate, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(predicate);
        return DeleteWhereAsync(EntityPredicate.Translate(predicate, Context.Json), ct);
    }

    public async Task<IReadOnlyList<Document>> AggregateAsync(ImmutableArray<PipelineStep> steps, bool dirtyRead, CancellationToken ct)
    {
        var request = Context.Encoder.Aggregate(Database, Collection, steps, dirtyRead, false);
        try
        {
            return ResponseDecoder.Documents(await SendAsync(Operation.Aggregate, request, ct).ConfigureAwait(false), "results");
        }
        catch (NotFoundException error) when (error.HasCode(ErrorCode.NoResults))
        {
            return [];
        }
    }

    public async Task<AnalyzeResult> AnalyzeAsync(ImmutableArray<PipelineStep> steps, bool dirtyRead, CancellationToken ct)
    {
        var reply = await SendAsync(Operation.Aggregate, Context.Encoder.Aggregate(Database, Collection, steps, dirtyRead, true), ct)
            .ConfigureAwait(false);
        return new AnalyzeResult(
            ResponseDecoder.Documents(reply, "results"),
            ResponseDecoder.DocumentOf(reply, "analyzeResult") ?? Document.Empty);
    }

    public Task<JsonObject> SendAsync(Operation operation, JsonObject request, CancellationToken ct) =>
        Context.Channel.SendAsync(operation, request, ct);

    private JsonObject Encode(T value)
    {
        var encoded = RequestEncoder.Document(Codec.Encode(value));
        if (encoded[Document.IdField] is { } id)
        {
            Names.DocumentId(ResponseDecoder.TextOf(id));
        }
        return encoded;
    }
}

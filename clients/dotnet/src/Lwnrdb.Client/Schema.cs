using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client;

public sealed class Schema
{
    private readonly ClientContext _context;
    private readonly string _database;
    private readonly string _collection;

    internal Schema(ClientContext context, string database, string collection)
    {
        _context = context;
        _database = database;
        _collection = collection;
    }

    public async Task<IReadOnlyList<string>> SaveAsync(Document schema, CancellationToken ct = default)
    {
        var request = RequestEncoder.SaveSchema(_database, _collection, RequestEncoder.Document(schema));
        var reply = await _context.Channel.SendAsync(Operation.SaveSchema, request, ct).ConfigureAwait(false);
        return ResponseDecoder.Strings(reply["warnings"]);
    }

    public async Task<Document?> GetAsync(CancellationToken ct = default)
    {
        try
        {
            var reply = await _context.Channel
                .SendAsync(Operation.GetSchema, RequestEncoder.Request("GET_SCHEMA", _database, _collection), ct)
                .ConfigureAwait(false);
            return ResponseDecoder.DocumentOf(reply, "schema");
        }
        catch (NotFoundException)
        {
            return null;
        }
    }

    public async Task DeleteAsync(CancellationToken ct = default) =>
        await _context.Channel
            .SendAsync(Operation.DeleteSchema, RequestEncoder.Request("DELETE_SCHEMA", _database, _collection), ct)
            .ConfigureAwait(false);
}

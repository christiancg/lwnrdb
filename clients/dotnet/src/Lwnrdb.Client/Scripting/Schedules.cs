using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Scripting;

public sealed class Schedules
{
    private readonly ClientContext _context;
    private readonly string _database;

    internal Schedules(ClientContext context, string database)
    {
        _context = context;
        _database = database;
    }

    public async Task<long> SaveAsync(ScheduleDefinition definition, CancellationToken ct = default)
    {
        var reply = await _context.Channel.SendAsync(Operation.SaveSchedule, ScriptRequestEncoder.SaveSchedule(_database, definition), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.WholeOf(reply["version"]) ?? 0;
    }

    public async Task DeleteAsync(string name, CancellationToken ct = default) =>
        await _context.Channel.SendAsync(Operation.DeleteSchedule, ScriptRequestEncoder.DeleteSchedule(_database, name), ct)
            .ConfigureAwait(false);

    public async Task<IReadOnlyList<ScheduleInfo>> ListAsync(bool? includeArgs = null, CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.ListSchedules, ScriptRequestEncoder.ListSchedules(_database, includeArgs), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "schedules").Select(Descriptors.Schedule)];
    }
}

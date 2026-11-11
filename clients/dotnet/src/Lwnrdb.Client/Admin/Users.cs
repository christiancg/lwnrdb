using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Protocol;
using Lwnrdb.Client.Query;

namespace Lwnrdb.Client.Admin;

public sealed class Users
{
    private readonly ClientContext _context;

    internal Users(ClientContext context)
    {
        _context = context;
    }

    public async Task CreateAsync(string username, string password, Permissions? permissions = null, CancellationToken ct = default) =>
        await _context.Channel.SendAsync(Operation.CreateUser, ScriptRequestEncoder.CreateUser(username, password, permissions), ct)
            .ConfigureAwait(false);

    public async Task DeleteAsync(string username, CancellationToken ct = default) =>
        await _context.Channel.SendAsync(Operation.DeleteUser, ScriptRequestEncoder.DeleteUser(username), ct).ConfigureAwait(false);

    public async Task ChangePermissionsAsync(string username, Permissions permissions, CancellationToken ct = default) =>
        await _context.Channel
            .SendAsync(Operation.ChangePermissions, ScriptRequestEncoder.ChangePermissions(username, permissions), ct)
            .ConfigureAwait(false);

    public async Task SetPasswordAsync(string username, string newPassword, CancellationToken ct = default) =>
        await _context.Channel
            .SendAsync(Operation.SetPassword, ScriptRequestEncoder.SetPassword(username, null, newPassword), ct)
            .ConfigureAwait(false);

    public async Task<IReadOnlyList<UserInfo>> ListAsync(Filter? filter = null, CancellationToken ct = default)
    {
        ImmutableArray<PipelineStep> steps = filter is null ? [] : [new FilterStep(filter)];
        try
        {
            var reply = await _context.Channel.SendAsync(Operation.ListUsers, _context.Scripts.ListUsers(steps), ct)
                .ConfigureAwait(false);
            return [.. ResponseDecoder.Documents(reply, "users").Select(Descriptors.User)];
        }
        catch (NotFoundException error) when (error.HasCode(ErrorCode.NoUsersFound) || error.HasCode(ErrorCode.NoResults))
        {
            return [];
        }
    }
}

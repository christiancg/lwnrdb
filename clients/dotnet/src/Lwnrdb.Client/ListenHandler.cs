using System;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;

namespace Lwnrdb.Client;

public sealed record ListenHandler<T>(Func<ListenUpdate<T>, CancellationToken, ValueTask> OnUpdate)
{
    public Func<ListenEndedException, ValueTask>? OnEnded { get; init; }

    public Func<Exception, ValueTask>? OnError { get; init; }
}

public static class ListenHandler
{
    public static ListenHandler<T> Of<T>(Action<ListenUpdate<T>> onUpdate)
    {
        ArgumentNullException.ThrowIfNull(onUpdate);
        return new((update, _) =>
        {
            onUpdate(update);
            return ValueTask.CompletedTask;
        });
    }
}

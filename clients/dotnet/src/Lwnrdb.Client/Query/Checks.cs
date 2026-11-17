using System;

namespace Lwnrdb.Client.Query;

internal static class Checks
{
    public static T NotNull<T>(T? value, string name)
        where T : class =>
        value ?? throw new ArgumentNullException(name);

    public static string Text(string? value, string name)
    {
        ArgumentException.ThrowIfNullOrEmpty(value, name);
        return value;
    }
}

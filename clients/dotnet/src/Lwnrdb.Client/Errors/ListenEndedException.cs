using System;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Errors;

public sealed class ListenEndedException : LwnrdbException
{
    public ListenEndedException()
    {
    }

    public ListenEndedException(string message)
        : base(message)
    {
    }

    public ListenEndedException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public ListenEndedException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}

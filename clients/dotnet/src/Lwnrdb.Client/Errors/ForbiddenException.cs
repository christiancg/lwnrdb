using System;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Errors;

public sealed class ForbiddenException : LwnrdbException
{
    public ForbiddenException()
    {
    }

    public ForbiddenException(string message)
        : base(message)
    {
    }

    public ForbiddenException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public ForbiddenException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}

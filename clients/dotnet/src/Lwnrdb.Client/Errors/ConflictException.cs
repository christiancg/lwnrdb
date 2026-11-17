using System;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Errors;

public sealed class ConflictException : LwnrdbException
{
    public ConflictException()
    {
    }

    public ConflictException(string message)
        : base(message)
    {
    }

    public ConflictException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public ConflictException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}

using System;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Errors;

public sealed class UnavailableException : LwnrdbException
{
    public UnavailableException()
    {
    }

    public UnavailableException(string message)
        : base(message)
    {
    }

    public UnavailableException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public UnavailableException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}

using System;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Errors;

public sealed class UnknownOutcomeException : LwnrdbException
{
    public UnknownOutcomeException()
    {
    }

    public UnknownOutcomeException(string message)
        : base(message)
    {
    }

    public UnknownOutcomeException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public UnknownOutcomeException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}

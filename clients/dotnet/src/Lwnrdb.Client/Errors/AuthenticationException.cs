using System;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Errors;

public sealed class AuthenticationException : LwnrdbException
{
    public AuthenticationException()
    {
    }

    public AuthenticationException(string message)
        : base(message)
    {
    }

    public AuthenticationException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public AuthenticationException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}

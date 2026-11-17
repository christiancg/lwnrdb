using System;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Errors;

public sealed class MappingException : LwnrdbException
{
    public MappingException()
    {
    }

    public MappingException(string message)
        : base(message)
    {
    }

    public MappingException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public MappingException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}

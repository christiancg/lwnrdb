using System;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Errors;

public class ConnectionLostException : LwnrdbException
{
    public ConnectionLostException()
    {
    }

    public ConnectionLostException(string message)
        : base(message)
    {
    }

    public ConnectionLostException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public ConnectionLostException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}

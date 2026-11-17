using System;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Errors;

public sealed class ServerClosedConnectionException : LwnrdbException
{
    public ServerClosedConnectionException()
    {
    }

    public ServerClosedConnectionException(string message)
        : base(message)
    {
    }

    public ServerClosedConnectionException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public ServerClosedConnectionException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}

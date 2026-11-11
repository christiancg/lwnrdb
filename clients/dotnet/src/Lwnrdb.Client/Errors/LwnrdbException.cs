using System;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Errors;

public class LwnrdbException : Exception
{
    public LwnrdbException()
    {
    }

    public LwnrdbException(string message)
        : base(message)
    {
    }

    public LwnrdbException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public LwnrdbException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, innerException)
    {
        RawCode = rawCode;
        Status = status;
        Operation = operation;
    }

    public string? RawCode { get; }

    public ErrorCode? Code => ErrorCode.Of(RawCode);

    public Status? Status { get; }

    public string? Operation { get; }

    public bool HasCode(string rawCode) => string.Equals(RawCode, rawCode, StringComparison.Ordinal);

    public bool HasCode(ErrorCode code)
    {
        ArgumentNullException.ThrowIfNull(code);
        return HasCode(code.Code);
    }
}

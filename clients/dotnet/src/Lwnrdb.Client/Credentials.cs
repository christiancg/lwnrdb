using System;
using System.Text;

namespace Lwnrdb.Client;

public sealed record Credentials
{
    public Credentials(string username, string password)
    {
        ArgumentException.ThrowIfNullOrEmpty(username);
        ArgumentNullException.ThrowIfNull(password);
        Username = username;
        Password = password;
    }

    public string Username { get; }

    public string Password { get; }

    private bool PrintMembers(StringBuilder builder)
    {
        builder.Append("Username = ").Append(Username).Append(", Password = ***");
        return true;
    }
}

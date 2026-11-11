using System;
using System.Text.RegularExpressions;

namespace Lwnrdb.Client.Internal;

internal static partial class Names
{
    private const string NameRule = "3-64 characters from A-Z a-z 0-9 _ -";
    private const string IdRule = "1-64 characters from A-Z a-z 0-9 _ -";

    public static string Database(string? name) => Check(name, NamePattern(), "database name", NameRule);

    public static string Collection(string? name) => Check(name, NamePattern(), "collection name", NameRule);

    public static string DocumentId(string? id) => Check(id, IdPattern(), "_id", IdRule);

    private static string Check(string? value, Regex pattern, string label, string rule)
    {
        if (value is null || !pattern.IsMatch(value))
        {
            throw new ArgumentException($"invalid {label} '{value}': it must be {rule}");
        }
        return value;
    }

    [GeneratedRegex("^[A-Za-z0-9_-]{3,64}$", RegexOptions.CultureInvariant)]
    private static partial Regex NamePattern();

    [GeneratedRegex("^[A-Za-z0-9_-]{1,64}$", RegexOptions.CultureInvariant)]
    private static partial Regex IdPattern();
}

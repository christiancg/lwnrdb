using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace Lwnrdb.DocGen;

internal sealed record DocSection(string Text, List<(string Tag, string Name, string Text)> Tags);

internal sealed record TypeDoc(string File, DocSection Summary, Dictionary<string, DocSection> Members);

internal static class ApiDocs
{
    private const string Heading = "## ";

    public static Dictionary<string, TypeDoc> Read(string folder) =>
        Directory.GetFiles(folder, "*.md").ToDictionary(file => Path.GetFileName(file), Parse, StringComparer.Ordinal);

    public static TypeDoc Parse(string file)
    {
        var lines = File.ReadAllLines(file);
        var members = new Dictionary<string, DocSection>(StringComparer.Ordinal);
        var current = new List<string>();
        string? currentName = null;
        DocSection? summary = null;
        foreach (var line in lines.Append(Heading))
        {
            if (!line.StartsWith(Heading, StringComparison.Ordinal))
            {
                current.Add(line);
                continue;
            }
            var section = Section(current);
            if (currentName is null)
            {
                summary ??= section;
            }
            else if (!members.TryAdd(currentName, section))
            {
                throw new InvalidDataException($"{Path.GetFileName(file)} documents '{currentName}' twice");
            }
            currentName = line[Heading.Length..].Trim().Replace("\\[", "[", StringComparison.Ordinal).Replace("\\]", "]", StringComparison.Ordinal);
            current = [];
        }
        return new TypeDoc(file, summary!, members);
    }

    private static DocSection Section(List<string> lines)
    {
        var text = new List<string>();
        var tags = new List<(string, string, string)>();
        foreach (var line in lines)
        {
            if (line.StartsWith('@'))
            {
                var parts = line.Split(' ', 3, StringSplitOptions.RemoveEmptyEntries);
                var named = parts[0] is "@param" or "@typeparam" or "@exception";
                tags.Add(named
                    ? (parts[0][1..], parts.ElementAtOrDefault(1) ?? string.Empty, parts.ElementAtOrDefault(2) ?? string.Empty)
                    : (parts[0][1..], string.Empty, string.Join(' ', parts.Skip(1))));
            }
            else if (tags.Count > 0 && line.Length > 0)
            {
                var (tag, name, value) = tags[^1];
                tags[^1] = (tag, name, value + " " + line.Trim());
            }
            else
            {
                text.Add(line);
            }
        }
        return new DocSection(string.Join('\n', text).Trim(), tags);
    }
}

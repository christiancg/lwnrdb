using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using System.Xml.Linq;

namespace Lwnrdb.DocGen;

internal static partial class XmlDocWriter
{
    public static XDocument Document(string assembly, IEnumerable<(string Id, DocSection Section)> members) => new(
        new XElement(
            "doc",
            new XElement("assembly", new XElement("name", assembly)),
            new XElement("members", members.Select(member => Member(member.Id, member.Section)))));

    private static XElement Member(string id, DocSection section)
    {
        var element = new XElement("member", new XAttribute("name", id));
        var paragraphs = section.Text.Split("\n\n", StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (paragraphs.Length > 0)
        {
            element.Add(new XElement("summary", Inline(paragraphs[0])));
        }
        if (paragraphs.Length > 1)
        {
            element.Add(new XElement("remarks", paragraphs.Skip(1).Select(paragraph => new XElement("para", Inline(paragraph)))));
        }
        foreach (var (tag, name, text) in section.Tags)
        {
            element.Add(tag switch
            {
                "param" => new XElement("param", new XAttribute("name", name), Inline(text)),
                "typeparam" => new XElement("typeparam", new XAttribute("name", name), Inline(text)),
                "exception" => new XElement("exception", new XAttribute("cref", "T:" + name), Inline(text)),
                "returns" => new XElement("returns", Inline(text)),
                _ => throw new FormatException($"unknown tag @{tag} in {id}"),
            });
        }
        return element;
    }

    private static IEnumerable<XNode> Inline(string markdown)
    {
        var text = string.Join(' ', markdown.Split('\n', StringSplitOptions.TrimEntries));
        var position = 0;
        foreach (Match match in Code().Matches(text))
        {
            yield return new XText(text[position..match.Index]);
            yield return new XElement("c", match.Groups[1].Value);
            position = match.Index + match.Length;
        }
        yield return new XText(text[position..]);
    }

    [GeneratedRegex("`([^`]+)`")]
    private static partial Regex Code();
}

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Lwnrdb.Client.Tests.Support;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;

namespace Lwnrdb.Client.Tests.Structure;

internal static class SourceFiles
{
    private static readonly string[] Roots = ["src", "tests", "build-tools", "consumers"];

    public static string DotnetRoot { get; } = Path.Combine(ProtocolFixtures.ClientsRoot, "dotnet");

    public static IEnumerable<string> Matching(string pattern) =>
        Roots.Select(root => Path.Combine(DotnetRoot, root))
            .Where(Directory.Exists)
            .SelectMany(root => Directory.EnumerateFiles(root, pattern, SearchOption.AllDirectories))
            .Where(path => !IsBuildOutput(path))
            .Order(StringComparer.Ordinal);

    public static IEnumerable<(string Path, SyntaxTree Tree)> CSharp() =>
        Matching("*.cs").Select(path => (path, CSharpSyntaxTree.ParseText(File.ReadAllText(path), path: path)));

    public static string Relative(string path) => Path.GetRelativePath(DotnetRoot, path);

    public static int LineOf(Location location) => location.GetLineSpan().StartLinePosition.Line + 1;

    private static bool IsBuildOutput(string path)
    {
        var segments = path.Split(Path.DirectorySeparatorChar);
        return segments.Contains("bin") || segments.Contains("obj") || segments.Contains("TestResults");
    }
}

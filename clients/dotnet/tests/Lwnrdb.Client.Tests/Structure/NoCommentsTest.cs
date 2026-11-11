using System.IO;
using System.Linq;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Xunit;

namespace Lwnrdb.Client.Tests.Structure;

public sealed class NoCommentsTest
{
    private static readonly SyntaxKind[] CommentKinds =
    [
        SyntaxKind.SingleLineCommentTrivia,
        SyntaxKind.MultiLineCommentTrivia,
        SyntaxKind.SingleLineDocumentationCommentTrivia,
        SyntaxKind.MultiLineDocumentationCommentTrivia,
    ];

    [Fact]
    public void NoCSharpFileHasAComment()
    {
        var comments = SourceFiles.CSharp()
            .SelectMany(file => file.Tree.GetRoot().DescendantTrivia(descendIntoTrivia: true)
                .Where(trivia => CommentKinds.Any(kind => trivia.IsKind(kind)))
                .Select(trivia => $"{SourceFiles.Relative(file.Path)}:{SourceFiles.LineOf(trivia.GetLocation())}"))
            .ToList();
        Assert.True(comments.Count == 0, "comments are not allowed:\n" + string.Join('\n', comments));
    }

    [Fact]
    public void NoProjectFileHasAComment()
    {
        var files = new[] { "*.csproj", "*.props", "*.slnx", "nuget.config" }
            .SelectMany(SourceFiles.Matching)
            .Concat(Directory.EnumerateFiles(SourceFiles.DotnetRoot, "*.*", SearchOption.TopDirectoryOnly)
                .Where(path => path.EndsWith(".props", System.StringComparison.Ordinal)
                    || path.EndsWith(".slnx", System.StringComparison.Ordinal)
                    || path.EndsWith("nuget.config", System.StringComparison.Ordinal)))
            .Distinct()
            .Where(path => File.ReadAllText(path).Contains("<!--", System.StringComparison.Ordinal))
            .Select(SourceFiles.Relative)
            .ToList();
        Assert.True(files.Count == 0, "comments are not allowed:\n" + string.Join('\n', files));
    }
}

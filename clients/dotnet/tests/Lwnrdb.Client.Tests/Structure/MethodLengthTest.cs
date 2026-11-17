using System.Linq;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Xunit;

namespace Lwnrdb.Client.Tests.Structure;

public sealed class MethodLengthTest
{
    private const int MaxLines = 200;

    [Fact]
    public void NoMethodAccessorOrLocalFunctionIsLongerThan200Lines()
    {
        var oversized = SourceFiles.CSharp()
            .SelectMany(file => file.Tree.GetRoot().DescendantNodes()
                .Where(node => node is BaseMethodDeclarationSyntax or AccessorDeclarationSyntax or LocalFunctionStatementSyntax)
                .Select(node => (file.Path, Node: node, Lines: LinesOf(node))))
            .Where(method => method.Lines > MaxLines)
            .Select(method => $"{SourceFiles.Relative(method.Path)}:{SourceFiles.LineOf(method.Node.GetLocation())} has {method.Lines} lines")
            .ToList();
        Assert.True(oversized.Count == 0, "split these methods:\n" + string.Join('\n', oversized));
    }

    private static int LinesOf(SyntaxNode node)
    {
        var span = node.GetLocation().GetLineSpan();
        return span.EndLinePosition.Line - span.StartLinePosition.Line + 1;
    }
}

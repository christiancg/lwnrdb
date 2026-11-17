using System.IO;
using System.Linq;
using Xunit;

namespace Lwnrdb.Client.Tests.Structure;

public sealed class FileLengthTest
{
    private const int MaxLines = 500;

    [Fact]
    public void NoCSharpFileIsLongerThan500Lines()
    {
        var oversized = SourceFiles.Matching("*.cs")
            .Select(path => (Path: path, Lines: File.ReadAllLines(path).Length))
            .Where(file => file.Lines > MaxLines)
            .Select(file => $"{SourceFiles.Relative(file.Path)} has {file.Lines} lines")
            .ToList();
        Assert.True(oversized.Count == 0, "split these files:\n" + string.Join('\n', oversized));
    }
}

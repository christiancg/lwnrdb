using System.IO.Pipelines;
using System.Text;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit.Internal;

public sealed class LineReaderTests
{
    private readonly Pipe _pipe = new();

    private LineReader Reader(long max = 1024) => new(_pipe.Reader.AsStream(), max);

    private async Task Write(params byte[][] chunks)
    {
        foreach (var chunk in chunks)
        {
            await _pipe.Writer.WriteAsync(chunk);
            await _pipe.Writer.FlushAsync();
        }
    }

    [Fact]
    public async Task ALineSplitAcrossReadsIsJoined()
    {
        using var reader = Reader();
        await Write("{\"a\""u8.ToArray(), ":1}\r\n"u8.ToArray());
        Assert.Equal(new LineEvent(LineKind.Text, "{\"a\":1}"), await reader.ReadLineAsync(default));
    }

    [Fact]
    public async Task SeveralLinesInOneReadAreSeparated()
    {
        using var reader = Reader();
        await Write("a\nb\n\nc\n"u8.ToArray());
        Assert.Equal("a", (await reader.ReadLineAsync(default)).Text);
        Assert.Equal("b", (await reader.ReadLineAsync(default)).Text);
        Assert.Equal(string.Empty, (await reader.ReadLineAsync(default)).Text);
        Assert.Equal("c", (await reader.ReadLineAsync(default)).Text);
    }

    [Fact]
    public async Task AMultiByteCharacterSplitAcrossReadsDecodes()
    {
        using var reader = Reader();
        var bytes = Encoding.UTF8.GetBytes("ñandú\n");
        await Write(bytes[..1], bytes[1..3], bytes[3..]);
        Assert.Equal("ñandú", (await reader.ReadLineAsync(default)).Text);
    }

    [Fact]
    public async Task AnOversizedLineIsReportedOnceAndTheNextLineIsRead()
    {
        using var reader = Reader(8);
        await Write("0123456789"u8.ToArray(), "abc\nok\n"u8.ToArray());
        Assert.Equal(LineKind.TooLarge, (await reader.ReadLineAsync(default)).Kind);
        Assert.Equal("ok", (await reader.ReadLineAsync(default)).Text);
    }

    [Fact]
    public async Task TheEndOfTheStreamIsReported()
    {
        using var reader = Reader();
        await _pipe.Writer.CompleteAsync();
        Assert.Equal(LineKind.End, (await reader.ReadLineAsync(default)).Kind);
    }
}

using System;
using System.Buffers;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Lwnrdb.Client.Internal;

internal enum LineKind
{
    Text,
    TooLarge,
    End,
}

internal readonly record struct LineEvent(LineKind Kind, string Text)
{
    public static LineEvent TooLarge { get; } = new(LineKind.TooLarge, string.Empty);

    public static LineEvent End { get; } = new(LineKind.End, string.Empty);
}

internal sealed class LineReader(Stream stream, long maxLineBytes) : IDisposable
{
    private const byte Newline = 0x0A;
    private const byte CarriageReturn = 0x0D;
    private const int ChunkBytes = 64 * 1024;

    private readonly byte[] _chunk = ArrayPool<byte>.Shared.Rent(ChunkBytes);
    private readonly ArrayBufferWriter<byte> _line = new();
    private int _start;
    private int _end;
    private bool _tooLarge;

    public async ValueTask<LineEvent> ReadLineAsync(CancellationToken ct)
    {
        while (true)
        {
            if (_start == _end)
            {
                _start = 0;
                _end = await stream.ReadAsync(_chunk.AsMemory(0, ChunkBytes), ct).ConfigureAwait(false);
                if (_end == 0)
                {
                    return LineEvent.End;
                }
            }
            var available = _chunk.AsSpan(_start, _end - _start);
            var newline = available.IndexOf(Newline);
            if (newline < 0)
            {
                Append(available);
                _start = _end;
                continue;
            }
            Append(available[..newline]);
            _start += newline + 1;
            return TakeLine();
        }
    }

    public void Dispose() => ArrayPool<byte>.Shared.Return(_chunk);

    private void Append(ReadOnlySpan<byte> segment)
    {
        if (segment.IsEmpty || _tooLarge)
        {
            return;
        }
        if (_line.WrittenCount + (long)segment.Length > maxLineBytes)
        {
            _tooLarge = true;
            _line.Clear();
            return;
        }
        _line.Write(segment);
    }

    private LineEvent TakeLine()
    {
        if (_tooLarge)
        {
            _tooLarge = false;
            return LineEvent.TooLarge;
        }
        var bytes = _line.WrittenSpan;
        if (!bytes.IsEmpty && bytes[^1] == CarriageReturn)
        {
            bytes = bytes[..^1];
        }
        var text = Encoding.UTF8.GetString(bytes);
        _line.ResetWrittenCount();
        return new LineEvent(LineKind.Text, text);
    }
}

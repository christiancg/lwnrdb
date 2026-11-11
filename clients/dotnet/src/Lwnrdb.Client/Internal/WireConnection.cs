using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Internal;

internal sealed class WireConnection : IDisposable
{
    private const string CloseConnection = "CLOSE_CONNECTION";
    private static readonly JsonDocumentOptions ParseOptions = new() { MaxDepth = 1024 };

    private readonly Stream _stream;
    private readonly LineReader _reader;
    private readonly SemaphoreSlim _writeGate = new(1, 1);
    private readonly Queue<PendingReply> _pending = new();
    private readonly CancellationTokenSource _closed = new();
    private readonly Lock _sync = new();
    private LwnrdbException? _closeCause;

    public WireConnection(Stream stream, long maxReplyBytes, string label)
    {
        _stream = stream;
        _reader = new LineReader(stream, maxReplyBytes);
        Label = label;
        _ = Task.Run(ReadLoopAsync);
    }

    public string Label { get; }

    public bool IsOpen => Volatile.Read(ref _closeCause) is null;

    public Action<JsonObject>? OnPush { get; set; }

    public Action<LwnrdbException>? OnClose { get; set; }

    public async Task<JsonObject> RequestAsync(JsonObject request, TimeSpan timeout, CancellationToken ct)
    {
        ThrowIfClosed();
        var reply = new PendingReply(ResponseDecoder.TextOf(request["type"]) == CloseConnection);
        var line = Encoding.UTF8.GetBytes(request.ToJsonString() + "\n");
        await _writeGate.WaitAsync(ct).ConfigureAwait(false);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
        deadline.CancelAfter(timeout);
        try
        {
            lock (_sync)
            {
                ThrowIfClosed();
                _pending.Enqueue(reply);
            }
            await _stream.WriteAsync(line, deadline.Token).ConfigureAwait(false);
            await _stream.FlushAsync(deadline.Token).ConfigureAwait(false);
        }
        catch (Exception error) when (error is not LwnrdbException)
        {
            throw Interrupted(error, timeout, ct);
        }
        finally
        {
            _writeGate.Release();
        }
        try
        {
            return await reply.Task.WaitAsync(deadline.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException error)
        {
            throw Interrupted(error, timeout, ct);
        }
    }

    public async Task CloseGracefullyAsync(TimeSpan timeout)
    {
        if (!IsOpen)
        {
            return;
        }
        try
        {
            await RequestAsync(RequestEncoder.Request(CloseConnection), timeout, CancellationToken.None).ConfigureAwait(false);
        }
        catch (LwnrdbException)
        {
            Close();
            return;
        }
        Close();
    }

    public void Dispose() => Close();

    public void Close() => Close(new ConnectionLostException("connection closed by the client"));

    public void Close(LwnrdbException cause)
    {
        PendingReply[] failed;
        lock (_sync)
        {
            if (_closeCause is not null)
            {
                return;
            }
            Volatile.Write(ref _closeCause, cause);
            failed = [.. _pending];
            _pending.Clear();
        }
        _closed.Cancel();
        _stream.Dispose();
        foreach (var reply in failed)
        {
            reply.Fail(cause);
        }
        OnClose?.Invoke(cause);
    }

    private Exception Interrupted(Exception error, TimeSpan timeout, CancellationToken ct)
    {
        if (ct.IsCancellationRequested)
        {
            Close(new ConnectionLostException("a request on this connection was cancelled after it was sent, so its outcome is unknown"));
            return new OperationCanceledException("the request was cancelled after it was sent; its outcome is unknown", error, ct);
        }
        if (error is OperationCanceledException)
        {
            var timedOut = new RequestTimeoutException(
                $"no reply within {timeout.TotalMilliseconds} ms; the connection was closed, so the request's outcome is unknown");
            Close(timedOut);
            return timedOut;
        }
        var lost = new ConnectionLostException($"could not send the request to {Label}: {error.Message}", error);
        Close(lost);
        return lost;
    }

    private void ThrowIfClosed()
    {
        var cause = Volatile.Read(ref _closeCause);
        if (cause is not null)
        {
            throw cause;
        }
    }

    private async Task ReadLoopAsync()
    {
        try
        {
            while (true)
            {
                var line = await _reader.ReadLineAsync(_closed.Token).ConfigureAwait(false);
                switch (line.Kind)
                {
                    case LineKind.End:
                        Close(new ConnectionLostException("the server closed the connection"));
                        return;
                    case LineKind.TooLarge:
                        FailHead(new LwnrdbException("the reply exceeded MaxReplyBytes and was discarded"));
                        break;
                    default:
                        OnLine(line.Text);
                        break;
                }
            }
        }
        catch (Exception error)
        {
            Close(new ConnectionLostException($"lost the connection to {Label}: {error.Message}", error));
        }
        finally
        {
            _reader.Dispose();
        }
    }

    private void OnLine(string text)
    {
        if (text.Length == 0)
        {
            return;
        }
        if (Parse(text) is not JsonObject frame)
        {
            FailHead(new LwnrdbException($"the server refused the request: {text}"));
            return;
        }
        bool awaitingClose;
        lock (_sync)
        {
            awaitingClose = _pending.TryPeek(out var head) && head.AwaitsClose;
        }
        switch (FrameRouter.Of(frame, awaitingClose))
        {
            case Route.Push:
                OnPush?.Invoke(frame);
                return;
            case Route.ServerClose:
                Close(new ServerClosedConnectionException(
                    $"the server closed the connection: {ResponseDecoder.TextOf(frame["message"])}",
                    ResponseDecoder.TextOf(frame["errorCode"]),
                    Status.Error,
                    CloseConnection));
                return;
            default:
                CompleteHead(frame);
                return;
        }
    }

    private static JsonNode? Parse(string text)
    {
        try
        {
            return JsonNode.Parse(text, documentOptions: ParseOptions);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private void CompleteHead(JsonObject frame)
    {
        PendingReply? head;
        lock (_sync)
        {
            _pending.TryDequeue(out head);
        }
        if (head is null)
        {
            Close(new ConnectionLostException($"the server sent a reply nobody asked for: {frame.ToJsonString()}"));
            return;
        }
        head.Complete(frame);
    }

    private void FailHead(LwnrdbException cause)
    {
        PendingReply? head;
        lock (_sync)
        {
            _pending.TryDequeue(out head);
        }
        head?.Fail(cause);
    }

    private sealed class PendingReply(bool awaitsClose)
    {
        private readonly TaskCompletionSource<JsonObject> _completion = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public bool AwaitsClose { get; } = awaitsClose;

        public Task<JsonObject> Task => _completion.Task;

        public void Complete(JsonObject frame) => _completion.TrySetResult(frame);

        public void Fail(LwnrdbException cause) => _completion.TrySetException(cause);
    }
}

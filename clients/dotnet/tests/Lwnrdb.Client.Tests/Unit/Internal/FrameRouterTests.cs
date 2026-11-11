using System.Text.Json.Nodes;
using Lwnrdb.Client.Internal;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit.Internal;

public sealed class FrameRouterTests
{
    [Fact]
    public void AListenUpdateIsAPush() =>
        Assert.Equal(Route.Push, FrameRouter.Of(new JsonObject { ["type"] = "LISTEN", ["isUpdate"] = true }, false));

    [Fact]
    public void AListenRegistrationReplyIsAResponse() =>
        Assert.Equal(Route.Response, FrameRouter.Of(new JsonObject { ["type"] = "LISTEN", ["listenId"] = "x" }, false));

    [Fact]
    public void AListenEndIsAPush()
    {
        var frame = new JsonObject { ["type"] = "LISTEN", ["errorCode"] = "410-1" };
        Assert.Equal(Route.Push, FrameRouter.Of(frame, false));
        Assert.True(FrameRouter.IsListenEnd(frame));
    }

    [Fact]
    public void AnUnaskedCloseIsAServerClose() =>
        Assert.Equal(Route.ServerClose, FrameRouter.Of(new JsonObject { ["type"] = "CLOSE_CONNECTION" }, false));

    [Fact]
    public void AnAskedCloseIsAResponse() =>
        Assert.Equal(Route.Response, FrameRouter.Of(new JsonObject { ["type"] = "CLOSE_CONNECTION" }, true));

    [Fact]
    public void EverythingElseIsAResponse() =>
        Assert.Equal(Route.Response, FrameRouter.Of(new JsonObject { ["type"] = "SAVE", ["isUpdate"] = true }, false));
}

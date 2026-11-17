using System;
using System.Linq;
using System.Text.Json.Nodes;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Tests.Support;
using Lwnrdb.Client.Values;
using Xunit;

namespace Lwnrdb.Client.Tests.Fixtures;

public sealed class ResponseFixtureTests
{
    private const string ErrorPrefix = "error:";

    public static TheoryData<string> FixtureNames() => [.. ProtocolFixtures.Responses().Select(fixture => fixture.Name)];

    [Theory]
    [MemberData(nameof(FixtureNames))]
    public void EachResponseDecodesToItsExpectation(string name)
    {
        var body = ProtocolFixtures.Responses().Single(fixture => fixture.Name == name).Body;
        var frame = body["frame"]!.AsObject();
        var expectation = body["expect"]!.GetValue<string>();
        switch (expectation)
        {
            case "ok":
                Assert.Equal(Route.Response, FrameRouter.Of(frame, false));
                Assert.Same(frame, ResponseDecoder.Check(frame));
                break;
            case "push":
                Assert.Equal(Route.Push, FrameRouter.Of(frame, false));
                Assert.False(FrameRouter.IsListenEnd(frame));
                break;
            case "listenEnded":
                Assert.Equal(Route.Push, FrameRouter.Of(frame, false));
                Assert.IsType<ListenEndedException>(ResponseDecoder.Raise(frame));
                break;
            case "serverClosed":
                Assert.Equal(Route.ServerClose, FrameRouter.Of(frame, false));
                Assert.Equal(Route.Response, FrameRouter.Of(frame, true));
                break;
            default:
                Assert.StartsWith(ErrorPrefix, expectation, StringComparison.Ordinal);
                ExpectError(frame, expectation[ErrorPrefix.Length..]);
                break;
        }
    }

    [Fact]
    public void CustomValuesInAReplyDecodeToTheirTypes()
    {
        var frame = ProtocolFixtures.Responses().Single(fixture => fixture.Name == "ok.find_by_id").Body["frame"]!.AsObject();
        var found = ResponseDecoder.DocumentOf(frame, "object")!;
        Assert.Equal(new DateTime(2024, 1, 1, 10, 0, 0), found.GetDateTime("openedAt"));
        Assert.Equal(new TimeOnly(9, 30), found.GetTimeOnly("opensAt"));
        Assert.Equal(new GeoPoint(40.71, -74.0), found.GetGeoPoint("location"));
        Assert.Equal(Vector.Of(0.12, 4.4e-4, -0.0, 1.0e21), found.GetVector("embedding"));
    }

    [Fact]
    public void TheListenPushFixtureDecodesTheNegativeZeroComponentWithItsSign()
    {
        var frame = ProtocolFixtures.Responses().Single(fixture => fixture.Name == "frames.listen_push").Body["frame"]!.AsObject();
        var row = ResponseDecoder.Documents(frame, "results")[0];
        Assert.True(double.IsNegative(row.GetVector("embedding")![2]));
    }

    private static void ExpectError(JsonObject frame, string code)
    {
        Assert.Equal(Route.Response, FrameRouter.Of(frame, false));
        var error = Assert.IsAssignableFrom<LwnrdbException>(Record.Exception(() => ResponseDecoder.Check(frame)));
        Assert.Equal(code, error.RawCode);
        Assert.Contains(code, error.Message, StringComparison.Ordinal);
        if (ErrorCode.Of(code)?.IsRetryable == true)
        {
            Assert.IsType<UnavailableException>(error);
        }
        if (code is "500-33" or "500-34")
        {
            Assert.IsType<UnknownOutcomeException>(error);
        }
    }
}

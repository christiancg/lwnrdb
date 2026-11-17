using System.Text.Json.Nodes;

namespace Lwnrdb.Client.Internal;

internal enum Route
{
    Response,
    Push,
    ServerClose,
}

internal static class FrameRouter
{
    public const string ListenEndedCode = "410-1";

    public static Route Of(JsonObject frame, bool awaitingClose)
    {
        var type = ResponseDecoder.TextOf(frame["type"]);
        if (type == "LISTEN" && IsPush(frame))
        {
            return Route.Push;
        }
        if (type == "CLOSE_CONNECTION" && !awaitingClose)
        {
            return Route.ServerClose;
        }
        return Route.Response;
    }

    public static bool IsListenEnd(JsonObject frame) => ResponseDecoder.TextOf(frame["errorCode"]) == ListenEndedCode;

    private static bool IsPush(JsonObject frame) =>
        ResponseDecoder.BooleanOf(frame["isUpdate"], false) || IsListenEnd(frame);
}

package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.JsonBuiltins.EJSON;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.host.FetchRequest;
import org.techhouse.simplejs.host.FetchResponse;
import org.techhouse.simplejs.host.FetchTarget;
import org.techhouse.simplejs.host.HostAllowlist;
import org.techhouse.simplejs.host.JdkNetworkAccess;
import org.techhouse.simplejs.host.JdkNetworkAccess.NetworkException;
import org.techhouse.simplejs.host.NetworkAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class FetchBuiltins {
    private static final String HOST_NOT_ALLOWED = "fetch: host not allowed";
    private static final String TOO_LARGE = "fetch response exceeds maximum size";

    private FetchBuiltins() {
    }

    public static void install(Environment global, EventLoop eventLoop, NetworkAccess network, ResourceLimits limits,
            Intrinsics intrinsics, InterpreterOps ops) {
        final var inFlight = new AtomicInteger();
        final var fetch = new JsNativeFunction("fetch",
                (_, args) -> fetch(eventLoop, network, limits, args, new Settlement(intrinsics, ops), inFlight));
        global.declareBuiltin("fetch", fetch);
    }

    private record Settlement(Intrinsics intrinsics, InterpreterOps ops) {
    }

    private static JsValue fetch(EventLoop eventLoop, NetworkAccess network, ResourceLimits limits, List<JsValue> args,
            Settlement settlement, AtomicInteger inFlight) {
        final var intrinsics = settlement.intrinsics();
        final var promise = new JsPromise(eventLoop);
        if (network == null || !limits.fetchEnabled()) {
            promise.reject(error("fetch is not available", intrinsics));
            return promise;
        }
        final var url = args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst(), settlement.ops());
        final var host = FetchTarget.host(url);
        if (host == null || !HostAllowlist.allows(limits.fetchHostAllowlist(), host)) {
            promise.reject(error(HOST_NOT_ALLOWED, intrinsics));
            return promise;
        }
        final var init = args.size() > 1 && args.get(1) instanceof JsObject object ? object : null;
        final var request = new FetchRequest(method(init, settlement.ops()), url, headers(init, settlement.ops()),
                body(init, settlement.ops()), limits.fetchTimeoutMillis(), limits.maxResponseBytes(),
                HostAllowlist.listsExactly(limits.fetchHostAllowlist(), host));
        if (inFlight.incrementAndGet() > limits.fetchMaxConcurrent()) {
            inFlight.decrementAndGet();
            promise.reject(error("fetch: too many concurrent requests", intrinsics));
            return promise;
        }
        runAsync(eventLoop, promise, network, request, settlement, inFlight);
        return promise;
    }

    private static void runAsync(EventLoop eventLoop, JsPromise promise, NetworkAccess network, FetchRequest request,
            Settlement settlement, AtomicInteger inFlight) {
        eventLoop.beginAsyncJob();
        Thread.ofVirtual().start(() -> {
            final var outcome = new FetchOutcome();
            try {
                perform(network, request, outcome);
            } finally {
                eventLoop.completeAsyncJob(() -> {
                    inFlight.decrementAndGet();
                    settle(promise, outcome, request.maxResponseBytes(), eventLoop, settlement);
                });
            }
        });
    }

    private static void perform(NetworkAccess network, FetchRequest request, FetchOutcome outcome) {
        final var timeout = request.timeoutMillis();
        if (timeout <= 0) {
            invoke(network, request, outcome);
            return;
        }
        final var worker = Thread.ofVirtual().start(() -> invoke(network, request, outcome));
        try {
            worker.join(timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            worker.interrupt();
            outcome.timedOut = true;
        }
    }

    private static void invoke(NetworkAccess network, FetchRequest request, FetchOutcome outcome) {
        try {
            outcome.response = network.fetch(request);
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError error) {
            outcome.error = error;
        }
    }

    private static void settle(JsPromise promise, FetchOutcome outcome, long maxResponseBytes, EventLoop eventLoop,
            Settlement settlement) {
        final var intrinsics = settlement.intrinsics();
        if (outcome.timedOut) {
            promise.reject(error("fetch timed out", intrinsics));
            return;
        }
        if (outcome.error instanceof NetworkException network && network.kind() != JdkNetworkAccess.Kind.FAILED) {
            promise.reject(
                    error(network.kind() == JdkNetworkAccess.Kind.BLOCKED ? HOST_NOT_ALLOWED : TOO_LARGE, intrinsics));
            return;
        }
        if (outcome.error != null || outcome.response == null) {
            promise.reject(error("fetch failed", intrinsics));
            return;
        }
        final var body = outcome.response.bodyText() == null ? "" : outcome.response.bodyText();
        if (maxResponseBytes >= 0 && body.getBytes(StandardCharsets.UTF_8).length > maxResponseBytes) {
            promise.reject(error(TOO_LARGE, intrinsics));
            return;
        }
        InterpreterOps.chargeChars(settlement.ops(), body.length());
        promise.resolve(makeResponse(outcome.response, body, eventLoop, intrinsics));
    }

    private static JsObject makeResponse(FetchResponse response, String body, EventLoop eventLoop,
            Intrinsics intrinsics) {
        final var result = new JsObject();
        final var status = response.status();
        result.set("ok", JsBoolean.of(status >= 200 && status < 300));
        result.set("status", new JsNumber(status));
        result.set("statusText", new JsString(response.statusText() == null ? "" : response.statusText()));
        result.set("headers", headerObject(response.headers()));
        result.set("text", new JsNativeFunction("text", (_, _) -> resolvedText(eventLoop, body)));
        result.set("json", new JsNativeFunction("json", (_, _) -> resolvedJson(eventLoop, body, intrinsics)));
        return result;
    }

    private static JsValue resolvedText(EventLoop eventLoop, String body) {
        final var promise = new JsPromise(eventLoop);
        promise.resolve(new JsString(body));
        return promise;
    }

    private static JsValue resolvedJson(EventLoop eventLoop, String body, Intrinsics intrinsics) {
        final var promise = new JsPromise(eventLoop);
        try {
            final var element = EJSON.fromJson("{\"v\":" + body + "}", JsonObject.class).get("v");
            promise.resolve(EJsonInterop.fromEjson(element));
        } catch (RuntimeException error) {
            promise.reject(error("invalid json response body", intrinsics));
        }
        return promise;
    }

    private static JsObject headerObject(Map<String, String> headers) {
        final var object = new JsObject();
        if (headers != null) {
            headers.forEach((key, value) -> object.set(key, new JsString(value)));
        }
        return object;
    }

    private static String method(JsObject init, InterpreterOps ops) {
        if (init != null && init.has("method")) {
            return JsCoercion.toStr(init.get("method"), ops).toUpperCase(Locale.ROOT);
        }
        return "GET";
    }

    private static Map<String, String> headers(JsObject init, InterpreterOps ops) {
        final var headers = new LinkedHashMap<String, String>();
        if (init != null && init.get("headers") instanceof JsObject object) {
            for (final var key : object.keys()) {
                headers.put(key, JsCoercion.toStr(object.get(key), ops));
            }
        }
        return headers;
    }

    private static String body(JsObject init, InterpreterOps ops) {
        if (init != null && init.has("body") && !(init.get("body") instanceof JsUndefined)) {
            return JsCoercion.toStr(init.get("body"), ops);
        }
        return null;
    }

    private static JsValue error(String message, Intrinsics intrinsics) {
        return intrinsics.makeError("TypeError", message);
    }

    private static final class FetchOutcome {
        private volatile FetchResponse response;
        private volatile Throwable error;
        private volatile boolean timedOut;
    }
}

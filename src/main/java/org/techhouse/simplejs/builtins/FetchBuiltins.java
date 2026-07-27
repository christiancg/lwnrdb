package org.techhouse.simplejs.builtins;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.host.FetchRequest;
import org.techhouse.simplejs.host.FetchResponse;
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
    private static final EJson EJSON = new EJson();

    private FetchBuiltins() {
    }

    public static void install(Environment global, EventLoop eventLoop, NetworkAccess network, ResourceLimits limits) {
        final var fetch = new JsNativeFunction("fetch", (_, args) -> fetch(eventLoop, network, limits, args));
        global.declareVar("fetch");
        global.assign("fetch", fetch);
    }

    private static JsValue fetch(EventLoop eventLoop, NetworkAccess network, ResourceLimits limits,
            List<JsValue> args) {
        final var promise = new JsPromise(eventLoop);
        if (network == null || !limits.fetchEnabled()) {
            promise.reject(error("fetch is not available"));
            return promise;
        }
        final var url = args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst());
        if (!allowed(url, limits.fetchHostAllowlist())) {
            promise.reject(error("fetch: host not allowed"));
            return promise;
        }
        final var init = args.size() > 1 && args.get(1) instanceof JsObject object ? object : null;
        final var request = new FetchRequest(method(init), url, headers(init), body(init), limits.fetchTimeoutMillis());
        runAsync(eventLoop, promise, network, request, limits.maxResponseBytes());
        return promise;
    }

    private static void runAsync(EventLoop eventLoop, JsPromise promise, NetworkAccess network, FetchRequest request,
            long maxResponseBytes) {
        eventLoop.beginAsyncJob();
        Thread.ofVirtual().start(() -> {
            final var outcome = new FetchOutcome();
            final var timeout = request.timeoutMillis();
            if (timeout > 0) {
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
            } else {
                invoke(network, request, outcome);
            }
            eventLoop.completeAsyncJob(() -> settle(promise, outcome, maxResponseBytes, eventLoop));
        });
    }

    private static void invoke(NetworkAccess network, FetchRequest request, FetchOutcome outcome) {
        try {
            outcome.response = network.fetch(request);
        } catch (RuntimeException error) {
            outcome.error = error;
        }
    }

    private static void settle(JsPromise promise, FetchOutcome outcome, long maxResponseBytes, EventLoop eventLoop) {
        if (outcome.timedOut) {
            promise.reject(error("fetch timed out"));
            return;
        }
        if (outcome.error != null) {
            promise.reject(error("fetch failed: " + message(outcome.error)));
            return;
        }
        if (outcome.response == null) {
            promise.reject(error("fetch failed"));
            return;
        }
        final var body = outcome.response.bodyText() == null ? "" : outcome.response.bodyText();
        if (maxResponseBytes >= 0 && body.length() > maxResponseBytes) {
            promise.reject(error("fetch response exceeds maximum size"));
            return;
        }
        promise.resolve(makeResponse(outcome.response, body, eventLoop));
    }

    private static JsObject makeResponse(FetchResponse response, String body, EventLoop eventLoop) {
        final var result = new JsObject();
        final var status = response.status();
        result.set("ok", JsBoolean.of(status >= 200 && status < 300));
        result.set("status", new JsNumber(status));
        result.set("statusText", new JsString(response.statusText() == null ? "" : response.statusText()));
        result.set("headers", headerObject(response.headers()));
        result.set("text", new JsNativeFunction("text", (_, _) -> resolvedText(eventLoop, body)));
        result.set("json", new JsNativeFunction("json", (_, _) -> resolvedJson(eventLoop, body)));
        return result;
    }

    private static JsValue resolvedText(EventLoop eventLoop, String body) {
        final var promise = new JsPromise(eventLoop);
        promise.resolve(new JsString(body));
        return promise;
    }

    private static JsValue resolvedJson(EventLoop eventLoop, String body) {
        final var promise = new JsPromise(eventLoop);
        try {
            final var element = EJSON.fromJson("{\"v\":" + body + "}", JsonObject.class).get("v");
            promise.resolve(EJsonInterop.fromEjson(element));
        } catch (RuntimeException error) {
            promise.reject(error("invalid json response body"));
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

    private static String method(JsObject init) {
        if (init != null && init.has("method")) {
            return JsCoercion.toStr(init.get("method")).toUpperCase(Locale.ROOT);
        }
        return "GET";
    }

    private static Map<String, String> headers(JsObject init) {
        final var headers = new LinkedHashMap<String, String>();
        if (init != null && init.get("headers") instanceof JsObject object) {
            for (final var key : object.keys()) {
                headers.put(key, JsCoercion.toStr(object.get(key)));
            }
        }
        return headers;
    }

    private static String body(JsObject init) {
        if (init != null && init.has("body") && !(init.get("body") instanceof JsUndefined)) {
            return JsCoercion.toStr(init.get("body"));
        }
        return null;
    }

    private static boolean allowed(String url, List<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) {
            return true;
        }
        return allowlist.contains(hostOf(url));
    }

    private static String hostOf(String url) {
        var host = url;
        final var scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        var end = host.length();
        for (final var delimiter : new char[]{'/', '?', '#'}) {
            final var index = host.indexOf(delimiter);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        host = host.substring(0, end);
        final var at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        final var colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return host;
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static JsValue error(String message) {
        return ErrorBuiltins.makeError("TypeError", message);
    }

    private static final class FetchOutcome {
        private FetchResponse response;
        private RuntimeException error;
        private boolean timedOut;
    }
}

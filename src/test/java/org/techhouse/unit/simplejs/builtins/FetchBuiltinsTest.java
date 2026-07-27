package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.FetchRequest;
import org.techhouse.simplejs.host.FetchResponse;
import org.techhouse.simplejs.host.NetworkAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.unit.simplejs.host.NetworkHostBindings;

public class FetchBuiltinsTest {
    private final SimpleJs engine = new SimpleJs();

    private static ResourceLimits fetchLimits() {
        return new ResourceLimits(-1, 2000, -1, false, true, List.of(), -1, -1);
    }

    private static ResourceLimits fetchLimits(List<String> allowlist, long maxBytes, long timeout) {
        return new ResourceLimits(-1, 2000, -1, false, true, allowlist, maxBytes, timeout);
    }

    private ScriptResult run(String source, NetworkAccess network, ResourceLimits limits) {
        return engine.run(source, NetworkHostBindings.of(network, limits));
    }

    private static NetworkAccess constant(FetchResponse response) {
        return _ -> response;
    }

    private static FetchResponse ok(String body) {
        return new FetchResponse(200, "OK", Map.of("content-type", "application/json"), body);
    }

    // A successful fetch resolves to a Response whose json() parses the body
    @Test
    public void test_fetch_json_happy_path() {
        final var source = "const r = await fetch('http://x/'); const j = await r.json(); return j.a;";
        final var result = run(source, constant(ok("{\"a\":42}")), fetchLimits());
        assertFalse(result.isError());
        assertEquals(42, result.getValue().asJsonNumber().asInteger());
    }

    // text() resolves to the raw response body
    @Test
    public void test_fetch_text() {
        final var source = "const r = await fetch('http://x/'); return await r.text();";
        final var result = run(source, constant(ok("hello")), fetchLimits());
        assertEquals("hello", result.getValue().asJsonString().getValue());
    }

    // ok/status reflect the HTTP status; a non-2xx status has ok=false
    @Test
    public void test_fetch_non_2xx() {
        final var response = new FetchResponse(404, "Not Found", Map.of(), "missing");
        final var source = "const r = await fetch('http://x/'); return [r.ok, r.status, r.statusText];";
        final var result = run(source, constant(response), fetchLimits());
        final var array = result.getValue().asJsonArray();
        assertFalse(array.get(0).asJsonBoolean().getValue());
        assertEquals(404, array.get(1).asJsonNumber().asInteger());
        assertEquals("Not Found", array.get(2).asJsonString().getValue());
    }

    // The request method, headers and body are forwarded to the NetworkAccess
    @Test
    public void test_fetch_forwards_request() {
        final var captured = new FetchRequest[1];
        final NetworkAccess network = request -> {
            captured[0] = request;
            return ok("{}");
        };
        final var source = "await fetch('http://x/y', { method: 'post', headers: { 'X-A': '1' }, body: 'payload' });"
                + " return 'done';";
        run(source, network, fetchLimits());
        assertEquals("POST", captured[0].method());
        assertEquals("http://x/y", captured[0].url());
        assertEquals("1", captured[0].headers().get("X-A"));
        assertEquals("payload", captured[0].bodyText());
    }

    // Without a NetworkAccess binding, fetch rejects with a catchable TypeError
    @Test
    public void test_fetch_missing_binding_rejects() {
        final var source = "try { await fetch('http://x/'); return 'no'; } catch (e) { return e.name + ':' + e.message; }";
        final var result = engine.run(source,
                new SimpleHostBindings(new org.techhouse.ejson.elements.JsonObject(), null, null, fetchLimits()));
        assertEquals("TypeError:fetch is not available", result.getValue().asJsonString().getValue());
    }

    // fetch disabled in limits rejects even when a NetworkAccess is present
    @Test
    public void test_fetch_disabled_rejects() {
        final var disabled = new ResourceLimits(-1, 2000, -1, false, false, List.of(), -1, -1);
        final var source = "try { await fetch('http://x/'); return 'no'; } catch (e) { return e.message; }";
        final var result = run(source, constant(ok("{}")), disabled);
        assertEquals("fetch is not available", result.getValue().asJsonString().getValue());
    }

    // A host outside the allowlist is rejected before any network call
    @Test
    public void test_fetch_allowlist_violation() {
        final var called = new boolean[1];
        final NetworkAccess network = _ -> {
            called[0] = true;
            return ok("{}");
        };
        final var source = "try { await fetch('http://evil.com/x'); return 'no'; } catch (e) { return e.message; }";
        final var result = run(source, network, fetchLimits(List.of("allowed.com"), -1, -1));
        assertEquals("fetch: host not allowed", result.getValue().asJsonString().getValue());
        assertFalse(called[0]);
    }

    // A host inside the allowlist is permitted
    @Test
    public void test_fetch_allowlist_allowed() {
        final var source = "const r = await fetch('https://allowed.com:8443/path?q=1'); return await r.text();";
        final var result = run(source, constant(ok("body")), fetchLimits(List.of("allowed.com"), -1, -1));
        assertEquals("body", result.getValue().asJsonString().getValue());
    }

    // A response larger than maxResponseBytes is rejected
    @Test
    public void test_fetch_size_violation() {
        final var source = "try { await fetch('http://x/'); return 'no'; } catch (e) { return e.message; }";
        final var result = run(source, constant(ok("0123456789")), fetchLimits(List.of(), 4, -1));
        assertEquals("fetch response exceeds maximum size", result.getValue().asJsonString().getValue());
    }

    // A network call slower than the timeout rejects
    @Test
    public void test_fetch_timeout() {
        final NetworkAccess slow = _ -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ok("{}");
        };
        final var source = "try { await fetch('http://x/'); return 'no'; } catch (e) { return e.message; }";
        final var result = run(source, slow, fetchLimits(List.of(), -1, 50));
        assertEquals("fetch timed out", result.getValue().asJsonString().getValue());
    }

    // An exception from the NetworkAccess rejects the promise
    @Test
    public void test_fetch_network_error() {
        final NetworkAccess failing = _ -> {
            throw new IllegalStateException("connection refused");
        };
        final var source = "try { await fetch('http://x/'); return 'no'; } catch (e) { return e.message; }";
        final var result = run(source, failing, fetchLimits());
        assertTrue(result.getValue().asJsonString().getValue().contains("connection refused"));
    }

    // A microtask queued before an await runs before the off-thread fetch settles (fetch is async)
    @Test
    public void test_fetch_runs_through_event_loop() {
        final var source = "const log = [];" + " Promise.resolve().then(() => log.push('micro'));"
                + " await fetch('http://x/');" + " log.push('fetch');" + " return log;";
        final var result = run(source, constant(ok("{}")), fetchLimits());
        final var log = result.getValue().asJsonArray();
        assertEquals("micro", log.get(0).asJsonString().getValue());
        assertEquals("fetch", log.get(1).asJsonString().getValue());
    }

    // Invalid JSON in the body rejects json() but not text()
    @Test
    public void test_fetch_invalid_json_rejects() {
        final var source = "const r = await fetch('http://x/');"
                + " try { await r.json(); return 'no'; } catch (e) { return e.message; }";
        final var result = run(source, constant(ok("not json")), fetchLimits());
        assertEquals("invalid json response body", result.getValue().asJsonString().getValue());
    }
}

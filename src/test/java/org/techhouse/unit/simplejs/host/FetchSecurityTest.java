package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.FetchRequest;
import org.techhouse.simplejs.host.FetchResponse;
import org.techhouse.simplejs.host.FetchTarget;
import org.techhouse.simplejs.host.JdkNetworkAccess.Kind;
import org.techhouse.simplejs.host.JdkNetworkAccess.NetworkException;
import org.techhouse.simplejs.host.NetworkAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class FetchSecurityTest {
    private static final String CATCH = "try { await fetch('%s'); return 'reached'; } catch (e) { return e.message; }";
    private static final FetchResponse OK = new FetchResponse(200, "OK", Map.of(), "ok");

    private final SimpleJs engine = new SimpleJs();

    private static ResourceLimits limits(List<String> allowlist, long timeout, int maxConcurrent) {
        return new ResourceLimits(-1, 10_000, -1, false, true, allowlist, -1, timeout, false, false, 16, 1000, 4096, -1,
                -1, -1, -1, maxConcurrent, ResourceLimits.UNLIMITED_ARRAY_LENGTH);
    }

    private String message(String url, NetworkAccess network, ResourceLimits limits) {
        final var result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> engine.run(CATCH.formatted(url), NetworkHostBindings.of(network, limits)));
        assertFalse(result.isError(), result.getErrorMessage());
        return result.getValue().asJsonString().getValue();
    }

    private static NetworkAccess failing(Supplier<? extends Throwable> failure) {
        return _ -> {
            final var thrown = failure.get();
            if (thrown instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw (Error) thrown;
        };
    }

    @Test
    public void test_default_host_has_no_network() {
        assertNull(SimpleHostBindings.empty().network());
    }

    @Test
    public void test_default_host_fetch_rejects() {
        final var source = "try { await fetch('https://example.com/'); return 'reached'; }"
                + " catch (e) { return e.message; }";
        final var result = engine.run(source, SimpleHostBindings.empty());
        assertFalse(result.isError());
        assertEquals("fetch is not available", result.getValue().asJsonString().getValue());
    }

    @Test
    public void test_blocked_address_message() {
        final var network = failing(() -> new NetworkException(Kind.BLOCKED, "10.0.0.5 is private"));
        assertEquals("fetch: host not allowed", message("http://x.example/", network, limits(List.of("*"), -1, 8)));
    }

    @Test
    public void test_too_large_kind_message() {
        final var network = failing(() -> new NetworkException(Kind.TOO_LARGE, "body exceeds the cap"));
        assertEquals("fetch response exceeds maximum size",
                message("http://x.example/", network, limits(List.of("*"), -1, 8)));
    }

    @Test
    public void test_generic_failure_message() {
        final var network = failing(() -> new IllegalStateException("Connection refused: 10.0.0.5:22"));
        final var text = message("http://x.example/", network, limits(List.of("*"), 2000, 8));
        assertEquals("fetch failed", text);
        assertFalse(text.contains("10.0.0.5"));
    }

    @Test
    public void test_error_in_network_access_settles_the_promise() {
        assertEquals("fetch failed",
                message("http://x.example/", failing(OutOfMemoryError::new), limits(List.of("*"), -1, 8)));
        assertEquals("fetch failed",
                message("http://x.example/", failing(StackOverflowError::new), limits(List.of("*"), 2000, 8)));
        assertEquals("fetch failed",
                message("http://x.example/", failing(() -> new LinkageError("missing")), limits(List.of("*"), -1, 8)));
    }

    @Test
    public void test_non_http_urls_are_not_allowed() {
        final var calls = new AtomicInteger();
        final NetworkAccess network = _ -> {
            calls.incrementAndGet();
            return OK;
        };
        for (final var url : List.of("file:///etc/passwd", "ftp://x.example/", "x.example/path", "https:///x")) {
            assertEquals("fetch: host not allowed", message(url, network, limits(List.of("*"), -1, 8)), url);
        }
        assertEquals(0, calls.get());
    }

    @Test
    public void test_allowlist_uses_uri_host() {
        final var captured = Collections.synchronizedList(new ArrayList<FetchRequest>());
        final NetworkAccess network = request -> {
            captured.add(request);
            return OK;
        };
        final var allowed = limits(List.of("allowed.com"), -1, 8);
        assertEquals("reached", message("https://allowed.com#@evil.com/", network, allowed));
        assertEquals("reached", message("https://evil.com@allowed.com/", network, allowed));
        assertEquals("fetch: host not allowed", message("https://evil.com\\\\@allowed.com/", network, allowed));
        assertEquals("fetch: host not allowed", message("https://allowed.com.evil.com/", network, allowed));
        assertEquals(2, captured.size());
        for (final var request : captured) {
            assertEquals("allowed.com", FetchTarget.host(request.url()));
        }
    }

    @Test
    public void test_exact_entry_sets_private_allowed() {
        final var captured = new FetchRequest[1];
        final NetworkAccess network = request -> {
            captured[0] = request;
            return OK;
        };
        message("http://10.0.0.5:8080/x", network, limits(List.of("*", "10.0.0.5"), 3000, 8));
        assertTrue(captured[0].privateAllowed());
        assertEquals(3000, captured[0].timeoutMillis());
        message("http://[::1]:8080/x", network, limits(List.of("::1"), -1, 8));
        assertTrue(captured[0].privateAllowed());
    }

    @Test
    public void test_wildcard_does_not_set_private_allowed() {
        final var captured = new FetchRequest[1];
        final NetworkAccess network = request -> {
            captured[0] = request;
            return OK;
        };
        message("http://10.0.0.5/x", network, limits(List.of("*"), -1, 8));
        assertFalse(captured[0].privateAllowed());
        message("https://a.example.com/x", network, limits(List.of("*.example.com"), -1, 8));
        assertFalse(captured[0].privateAllowed());
    }

    @Test
    public void test_concurrent_cap_rejects_ninth_fetch() throws InterruptedException {
        final var started = new AtomicInteger();
        final var release = new CountDownLatch(1);
        final NetworkAccess blocking = _ -> {
            started.incrementAndGet();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return OK;
        };
        final var releaser = Thread.ofVirtual().start(() -> {
            while (started.get() < 8) {
                Thread.onSpinWait();
            }
            release.countDown();
        });
        final var source = "const all = []; for (let i = 0; i < 9; i++) {"
                + " all.push(fetch('http://x.example/' + i).then(() => 'ok', e => e.message)); }"
                + " return await Promise.all(all);";
        final var result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> engine.run(source, NetworkHostBindings.of(blocking, limits(List.of("*"), -1, 8))));
        releaser.join(5000);
        final var answers = result.getValue().asJsonArray().asList().stream()
                .map(element -> element.asJsonString().getValue()).toList();
        assertEquals(8, answers.stream().filter("ok"::equals).count());
        assertEquals("fetch: too many concurrent requests", answers.get(8));
    }

    @Test
    public void test_cap_released_after_settle() {
        final NetworkAccess network = _ -> OK;
        final var source = "await fetch('http://x.example/1'); await fetch('http://x.example/2');"
                + " const pair = [fetch('http://x.example/3'), fetch('http://x.example/4')];"
                + " return await Promise.allSettled(pair).then(r => r.map(s => s.status + ':' + (s.reason ? s.reason.message : '')));";
        final var result = engine.run(source, NetworkHostBindings.of(network, limits(List.of("*"), -1, 1)));
        final var answers = result.getValue().asJsonArray();
        assertEquals("fulfilled:", answers.get(0).asJsonString().getValue());
        assertEquals("rejected:fetch: too many concurrent requests", answers.get(1).asJsonString().getValue());
    }
}

package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class FetchSecurityTest {
    private final SimpleJs engine = new SimpleJs();

    // The default host exposes no network binding: fetch is secure-by-default
    @Test
    public void test_default_host_has_no_network() {
        assertNull(SimpleHostBindings.empty().network());
    }

    // A script running under the default host cannot reach the network
    @Test
    public void test_default_host_fetch_rejects() {
        final var source = "try { await fetch('http://example.com/'); return 'reached'; }"
                + " catch (e) { return e.message; }";
        final var result = engine.run(source, SimpleHostBindings.empty());
        assertFalse(result.isError());
        assertEquals("fetch is not available", result.getValue().asJsonString().getValue());
    }
}

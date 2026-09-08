package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.host.HostAllowlist;

public class HostAllowlistTest {
    @Test
    public void test_exact_host_matches() {
        assertTrue(HostAllowlist.allows(List.of("api.example.com"), "api.example.com"));
        assertFalse(HostAllowlist.allows(List.of("api.example.com"), "other.example.com"));
    }

    @Test
    public void test_matching_is_case_insensitive_and_trimmed() {
        assertTrue(HostAllowlist.allows(List.of("  API.Example.COM  "), "api.example.com"));
        assertTrue(HostAllowlist.allows(List.of("api.example.com"), "API.EXAMPLE.COM"));
    }

    // A wildcard behaves like a certificate wildcard: sub-domains, not the apex.
    @Test
    public void test_wildcard_matches_subdomains_but_not_the_apex() {
        assertTrue(HostAllowlist.allows(List.of("*.example.com"), "api.example.com"));
        assertTrue(HostAllowlist.allows(List.of("*.example.com"), "a.b.example.com"));
        assertFalse(HostAllowlist.allows(List.of("*.example.com"), "example.com"));
        assertFalse(HostAllowlist.allows(List.of("*.example.com"), "notexample.com"));
        assertFalse(HostAllowlist.allows(List.of("*.example.com"), "example.com.evil.net"));
    }

    @Test
    public void test_star_allows_every_host() {
        assertTrue(HostAllowlist.allows(List.of("*"), "anything.internal"));
        assertTrue(HostAllowlist.allows(List.of("api.example.com", "*"), "elsewhere.net"));
    }

    // The inverted default: enabling fetch without naming a host is not a decision to allow everything.
    @Test
    public void test_an_empty_list_denies_everything() {
        assertFalse(HostAllowlist.allows(List.of(), "api.example.com"));
        assertFalse(HostAllowlist.allows(null, "api.example.com"));
        assertFalse(HostAllowlist.allows(List.of("  "), "api.example.com"));
    }

    @Test
    public void test_a_blank_host_is_never_allowed() {
        assertFalse(HostAllowlist.allows(List.of("*"), null));
        assertFalse(HostAllowlist.allows(List.of("*"), "  "));
    }

    @Test
    public void test_a_null_entry_is_skipped_rather_than_fatal() {
        assertTrue(HostAllowlist.allows(Arrays.asList(null, "api.example.com"), "api.example.com"));
    }

    @Test
    public void test_allows_everything_detects_the_total_allow_entry() {
        assertTrue(HostAllowlist.allowsEverything(List.of("api.example.com", " * ")));
        assertFalse(HostAllowlist.allowsEverything(List.of("*.example.com")));
        assertFalse(HostAllowlist.allowsEverything(List.of()));
        assertFalse(HostAllowlist.allowsEverything(null));
    }
}

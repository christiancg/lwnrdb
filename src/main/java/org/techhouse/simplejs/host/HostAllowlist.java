package org.techhouse.simplejs.host;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether a script may reach a host.
 *
 * <p>
 * An entry is an exact host name, a {@code *.example.com} wildcard matching any sub-domain (but not the apex
 * - a certificate wildcard behaves the same way), or a bare {@code *} allowing everything. An
 * <em>empty</em> list denies: an operator who enables {@code fetch} without naming a host has not decided
 * where scripts may reach, and unrestricted egress is the wrong thing to infer from silence. Total allow
 * remains available, but it has to be typed.
 */
public final class HostAllowlist {
    private static final String MATCH_EVERYTHING = "*";
    private static final String WILDCARD_PREFIX = "*.";

    private HostAllowlist() {
    }

    public static boolean allows(List<String> allowlist, String host) {
        if (allowlist == null || allowlist.isEmpty() || host == null || host.isBlank()) {
            return false;
        }
        final var candidate = host.toLowerCase(Locale.ROOT);
        for (final var raw : allowlist) {
            if (raw == null) {
                continue;
            }
            final var entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            if (MATCH_EVERYTHING.equals(entry)) {
                return true;
            }
            if (entry.startsWith(WILDCARD_PREFIX)) {
                if (candidate.endsWith(entry.substring(1)) && candidate.length() > entry.length() - 1) {
                    return true;
                }
                continue;
            }
            if (entry.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static boolean allowsEverything(List<String> allowlist) {
        if (allowlist == null) {
            return false;
        }
        for (final var entry : allowlist) {
            if (entry != null && MATCH_EVERYTHING.equals(entry.trim())) {
                return true;
            }
        }
        return false;
    }
}

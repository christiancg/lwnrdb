package org.techhouse.simplejs.host;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

public final class FetchTarget {
    private static final Set<String> SCHEMES = Set.of("http", "https");

    private FetchTarget() {
    }

    public static URI parse(String url) {
        if (url == null) {
            return null;
        }
        try {
            final var uri = new URI(url);
            final var scheme = uri.getScheme();
            if (scheme == null || !SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)) || uri.getHost() == null) {
                return null;
            }
            return uri;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    public static String host(URI uri) {
        final var host = uri.getHost();
        return host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']'
                ? host.substring(1, host.length() - 1)
                : host;
    }

    public static String host(String url) {
        final var uri = parse(url);
        return uri == null ? null : host(uri);
    }
}

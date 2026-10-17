package org.techhouse.simplejs.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.techhouse.log.Logger;

public final class JdkNetworkAccess implements NetworkAccess {
    private static final Logger logger = Logger.logFor(JdkNetworkAccess.class);
    private static final int CHUNK_BYTES = 8192;
    private static final String CHARSET_PARAMETER = "charset=";

    private final HttpClient client;

    public JdkNetworkAccess() {
        this(HttpClient.newHttpClient());
    }

    public JdkNetworkAccess(HttpClient client) {
        this.client = client;
    }

    @Override
    public FetchResponse fetch(FetchRequest request) {
        final var uri = FetchTarget.parse(request.url());
        if (uri == null) {
            throw new NetworkException(Kind.BLOCKED, "not an http or https url with a host");
        }
        final var host = FetchTarget.host(uri);
        final var addresses = resolve(host);
        if (!request.privateAllowed() && !addresses.stream().allMatch(PublicAddressPolicy::isPublic)) {
            throw new NetworkException(Kind.BLOCKED, "host resolves to a non-public address");
        }
        try (var _ = PinnedResolution.pin(host, addresses)) {
            return send(uri, host, request);
        }
    }

    private static List<InetAddress> resolve(String host) {
        try {
            return List.of(InetAddress.getAllByName(host));
        } catch (UnknownHostException unknown) {
            logger.info("fetch could not resolve " + host + ": " + unknown.getMessage());
            throw new NetworkException(Kind.FAILED, "host could not be resolved");
        }
    }

    private FetchResponse send(URI uri, String host, FetchRequest request) {
        final var builder = HttpRequest.newBuilder(uri);
        if (request.timeoutMillis() > 0) {
            builder.timeout(Duration.ofMillis(request.timeoutMillis()));
        }
        if (request.headers() != null) {
            request.headers().forEach(builder::header);
        }
        final var body = request.bodyText() == null
                ? BodyPublishers.noBody()
                : BodyPublishers.ofString(request.bodyText());
        builder.method(request.method() == null ? "GET" : request.method(), body);
        try {
            final var response = client.send(builder.build(), BodyHandlers.ofInputStream());
            final var text = readCapped(response, request.maxResponseBytes());
            return new FetchResponse(response.statusCode(), reasonPhrase(response.statusCode()),
                    firstValueHeaders(response), text);
        } catch (IOException error) {
            logger.info("fetch to " + host + " failed: " + error);
            throw new NetworkException(Kind.FAILED, "network error");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new NetworkException(Kind.FAILED, "network request interrupted");
        }
    }

    static String readCapped(HttpResponse<InputStream> response, long maxBytes) throws IOException {
        try (var stream = response.body()) {
            final var declared = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (maxBytes >= 0 && declared > maxBytes) {
                throw new NetworkException(Kind.TOO_LARGE, "declared body exceeds the cap");
            }
            final var out = new ByteArrayOutputStream();
            final var chunk = new byte[CHUNK_BYTES];
            var read = stream.read(chunk);
            while (read != -1) {
                if (maxBytes >= 0 && out.size() + (long) read > maxBytes) {
                    throw new NetworkException(Kind.TOO_LARGE, "body exceeds the cap");
                }
                out.write(chunk, 0, read);
                read = stream.read(chunk);
            }
            return out.toString(charsetOf(response.headers().firstValue("content-type").orElse("")));
        }
    }

    public static Charset charsetOf(String contentType) {
        final var lower = contentType.toLowerCase(Locale.ROOT);
        final var at = lower.indexOf(CHARSET_PARAMETER);
        if (at < 0) {
            return StandardCharsets.UTF_8;
        }
        var name = contentType.substring(at + CHARSET_PARAMETER.length());
        final var end = name.indexOf(';');
        name = (end >= 0 ? name.substring(0, end) : name).trim().replace("\"", "");
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException unsupported) {
            return StandardCharsets.UTF_8;
        }
    }

    private static Map<String, String> firstValueHeaders(HttpResponse<?> response) {
        final var headers = new LinkedHashMap<String, String>();
        response.headers().map().forEach((key, values) -> {
            if (!values.isEmpty()) {
                headers.put(key, values.getFirst());
            }
        });
        return headers;
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "";
        };
    }

    public enum Kind {
        BLOCKED, TOO_LARGE, FAILED
    }

    public static final class NetworkException extends RuntimeException {
        private final Kind kind;

        public NetworkException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}

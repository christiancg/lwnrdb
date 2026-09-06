package org.techhouse.simplejs.host;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

// The single place in the SimpleJS engine that performs real network I/O. A host wires this into its
// HostBindings.network() to opt scripts into `fetch`; it is never installed by default.
public final class JdkNetworkAccess implements NetworkAccess {
    private final HttpClient client;

    public JdkNetworkAccess() {
        this(HttpClient.newHttpClient());
    }

    public JdkNetworkAccess(HttpClient client) {
        this.client = client;
    }

    @Override
    public FetchResponse fetch(FetchRequest request) {
        final var builder = HttpRequest.newBuilder(URI.create(request.url()));
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
            final var response = client.send(builder.build(), BodyHandlers.ofString());
            return new FetchResponse(response.statusCode(), reasonPhrase(response.statusCode()),
                    firstValueHeaders(response), response.body());
        } catch (IOException error) {
            throw new NetworkException(error.getMessage() == null ? "network error" : error.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new NetworkException("network request interrupted");
        }
    }

    private static Map<String, String> firstValueHeaders(HttpResponse<String> response) {
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

    public static final class NetworkException extends RuntimeException {
        public NetworkException(String message) {
            super(message);
        }
    }
}

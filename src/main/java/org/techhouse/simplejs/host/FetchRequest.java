package org.techhouse.simplejs.host;

import java.util.Map;

public record FetchRequest(String method, String url, Map<String, String> headers, String bodyText,
        long timeoutMillis) {
}

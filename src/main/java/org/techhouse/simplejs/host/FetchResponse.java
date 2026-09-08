package org.techhouse.simplejs.host;

import java.util.Map;

public record FetchResponse(int status, String statusText, Map<String, String> headers, String bodyText) {
}

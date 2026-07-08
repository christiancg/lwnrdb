package org.techhouse.cluster.msg;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes forwarded request/response JSON as Base64 for transport in a {@link ClusterMessage#getForwardBody()}
 * string field. EJson does not escape string values, so raw JSON (which contains quotes and braces) cannot be
 * embedded directly in an outer JSON message without corrupting it; Base64 keeps the body to a safe alphabet.
 */
public final class ForwardBody {
    private ForwardBody() {
    }

    public static String encode(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}

package org.techhouse.cluster;

import java.io.IOException;
import java.io.Serial;

/**
 * The peer could not be connected to, so the request was never put on the wire. That is the one failure a
 * caller can treat as "the other node definitely did not do this": every other failure - a timeout, a reset
 * mid-request - leaves the peer possibly still executing, and retrying elsewhere would run the work twice.
 */
public class PeerUnreachableException extends IOException {
    @Serial
    private static final long serialVersionUID = 1L;

    public PeerUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}

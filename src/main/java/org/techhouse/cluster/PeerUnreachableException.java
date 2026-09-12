package org.techhouse.cluster;

import java.io.IOException;
import java.io.Serial;

/**
 * The request was never put on the wire, so the peer definitely did not do it; every other failure leaves
 * it possibly still executing, and retrying elsewhere would run the work twice.
 */
public class PeerUnreachableException extends IOException {
    @Serial
    private static final long serialVersionUID = 1L;

    public PeerUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}

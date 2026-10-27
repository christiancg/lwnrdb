package org.techhouse.listen;

import java.util.HexFormat;
import org.techhouse.ejson.internal.EncodedJson;
import org.techhouse.ejson.internal.Utf8JsonOut;

public final class EncodedResults implements AutoCloseable {
    private final Utf8JsonOut out;
    private final String hash;
    private EncodedJson body;

    EncodedResults(Utf8JsonOut out, byte[] digest) {
        this.out = out;
        this.hash = HexFormat.of().formatHex(digest);
    }

    public String hash() {
        return hash;
    }

    public EncodedJson body() {
        if (body == null) {
            body = out.handOver();
        }
        return body;
    }

    @Override
    public void close() {
        out.release();
    }
}

package org.techhouse.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class HeldOutput extends OutputStream {
    private final OutputStream target;
    private final int holdBytes;
    private ByteArrayOutputStream held = new ByteArrayOutputStream();

    HeldOutput(OutputStream target, int holdBytes) {
        this.target = target;
        this.holdBytes = holdBytes;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (held != null && held.size() + length <= holdBytes) {
            held.write(bytes, offset, length);
            return;
        }
        release();
        target.write(bytes, offset, length);
    }

    boolean spilled() {
        return held == null;
    }

    void release() throws IOException {
        if (held != null) {
            held.writeTo(target);
            held = null;
        }
    }
}

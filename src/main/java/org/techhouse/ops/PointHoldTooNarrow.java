package org.techhouse.ops;

public final class PointHoldTooNarrow extends RuntimeException {
    public PointHoldTooNarrow() {
        super("A pipeline running under a point hold needs to stream a page", null, false, false);
    }
}

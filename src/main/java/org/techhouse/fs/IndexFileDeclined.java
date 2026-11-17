package org.techhouse.fs;

final class IndexFileDeclined extends RuntimeException {
    IndexFileDeclined() {
        super(null, null, false, false);
    }
}

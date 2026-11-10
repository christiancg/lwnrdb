package org.techhouse.fs;

import java.io.IOException;

final class RewriteOpenException extends IOException {
    RewriteOpenException(String message) {
        super(message);
    }
}

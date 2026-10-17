package org.techhouse.fs;

import java.io.IOException;
import java.io.OutputStream;

@FunctionalInterface
interface LineWriter {
    void writeTo(OutputStream out) throws IOException;
}

package org.techhouse.fs.io;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class AppendingFile implements Closeable {
    private final Path file;
    private final OutputStream out;
    private long length;

    private AppendingFile(Path file, OutputStream out, long length) {
        this.file = file;
        this.out = out;
        this.length = length;
    }

    public static AppendingFile open(Path file, int bufferSize) throws IOException {
        OwnerOnlyFiles.ensureOwnerOnlyFile(file);
        final var out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        final var length = Files.size(file);
        return new AppendingFile(file, new BufferedOutputStream(out, bufferSize), length);
    }

    public void append(String text) throws IOException {
        final var bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        length += bytes.length;
    }

    public void flush() throws IOException {
        out.flush();
    }

    public long length() {
        return length;
    }

    public boolean isPresent() {
        return Files.exists(file);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}

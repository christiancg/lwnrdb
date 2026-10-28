package org.techhouse.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

@FunctionalInterface
interface Forcer {
    Forcer DEVICE = (channel, _) -> channel.force(false);

    void force(FileChannel channel, Path path) throws IOException;
}

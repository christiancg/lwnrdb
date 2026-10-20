package org.techhouse.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class NodeIdStore {
    private final FilePaths paths;

    NodeIdStore(FilePaths paths) {
        this.paths = paths;
    }

    Path file() {
        return paths.nodeIdFile().toPath();
    }

    String read() throws IOException {
        final var path = file();
        if (!Files.exists(path)) {
            return null;
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    void write(String nodeId) throws IOException {
        final var path = file();
        final var parent = path.getParent();
        if (parent != null) {
            OwnerOnly.createDirectories(parent);
        }
        final var tmp = path.resolveSibling(path.getFileName() + ".tmp");
        OwnerOnly.ensureOwnerOnlyFile(tmp);
        Files.writeString(tmp, nodeId, StandardCharsets.UTF_8);
        SyncedWrites.content(tmp);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        SyncedWrites.entriesOfLanded(path.toAbsolutePath().getParent(), "Writing the node id");
    }
}

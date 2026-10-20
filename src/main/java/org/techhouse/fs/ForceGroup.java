package org.techhouse.fs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ForceGroup {
    private final Set<Path> paths = new LinkedHashSet<>();
    private final List<Runnable> onceForced = new ArrayList<>();

    void add(Path path) {
        if (SyncedWrites.enabled()) {
            paths.add(path.toAbsolutePath().normalize());
        }
    }

    void onceForced(Runnable action) {
        onceForced.add(action);
    }

    void force() throws IOException {
        SyncedWrites.contentTogether(List.copyOf(paths));
        paths.clear();
        final var actions = List.copyOf(onceForced);
        onceForced.clear();
        actions.forEach(Runnable::run);
    }
}

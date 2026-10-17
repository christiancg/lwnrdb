package org.techhouse.listen;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ops.ErrorCode;

public class ListenRetention {
    private final Configuration configuration = Configuration.getInstance();
    private final Map<String, Integer> listensPerUser = new HashMap<>();
    private long retainedBytes;

    public synchronized ErrorCode reserve(String username, long bytes) {
        final var user = keyOf(username);
        final var userLimit = configuration.intValue(ConfigKey.MAX_LISTENS_PER_USER);
        final var held = listensPerUser.getOrDefault(user, 0);
        if (userLimit != 0 && held >= userLimit) {
            return ErrorCode.TOO_MANY_LISTENS_FOR_USER;
        }
        if (retainedBytes + bytes > configuration.sizeValue(ConfigKey.LISTEN_MAX_RETAINED_BYTES)) {
            return ErrorCode.LISTEN_RETAINED_BYTES_EXHAUSTED;
        }
        listensPerUser.put(user, held + 1);
        retainedBytes += bytes;
        return null;
    }

    public synchronized boolean hasUserCapacity(String username) {
        final var userLimit = configuration.intValue(ConfigKey.MAX_LISTENS_PER_USER);
        return userLimit == 0 || listensPerUser.getOrDefault(keyOf(username), 0) < userLimit;
    }

    public synchronized void release(String username, long bytes) {
        final var user = keyOf(username);
        listensPerUser.computeIfPresent(user, (_, held) -> held > 1 ? held - 1 : null);
        retainedBytes -= bytes;
    }

    public synchronized long retainedBytes() {
        return retainedBytes;
    }

    public synchronized int listensOf(String username) {
        return listensPerUser.getOrDefault(keyOf(username), 0);
    }

    private static String keyOf(String username) {
        return Objects.requireNonNullElse(username, "");
    }
}

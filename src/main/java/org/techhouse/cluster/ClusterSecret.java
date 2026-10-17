package org.techhouse.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.techhouse.ioc.IocContainer;

public final class ClusterSecret {
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);

    private ClusterSecret() {
    }

    public static boolean matches(String presented) {
        final var expected = clusterConfig.secret();
        if (presented == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}

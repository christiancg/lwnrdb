package org.techhouse.cluster.ownership;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.TreeMap;

public final class HashRing {
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String VIRTUAL_NODE_SEPARATOR = "#";
    private final TreeMap<Long, String> ring = new TreeMap<>();

    public HashRing(Collection<String> nodeIds, int virtualNodesPerNode) {
        for (var nodeId : nodeIds) {
            for (var i = 0; i < virtualNodesPerNode; i++) {
                ring.put(hash(nodeId + VIRTUAL_NODE_SEPARATOR + i), nodeId);
            }
        }
    }

    public boolean isEmpty() {
        return ring.isEmpty();
    }

    public String owner(String key) {
        if (ring.isEmpty()) {
            return null;
        }
        final var entry = ring.ceilingEntry(hash(key));
        return entry != null ? entry.getValue() : ring.firstEntry().getValue();
    }

    private static long hash(String value) {
        try {
            final var digest = MessageDigest.getInstance(HASH_ALGORITHM);
            final var bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            long result = 0L;
            for (var i = 0; i < Long.BYTES; i++) {
                result = (result << 8) | (bytes[i] & 0xFFL);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing " + HASH_ALGORITHM + " algorithm", e);
        }
    }
}

package org.techhouse.listen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.AggregateRequest;

final class ListenGroups {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private final Map<String, ListenGroup> byKey = new ConcurrentHashMap<>();
    private final Map<UUID, ListenGroup> byId = new ConcurrentHashMap<>();

    static String keyOf(String username, AggregateRequest request) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(username).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(eJson.toJson(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    ListenGroup join(String key, AggregateRequest request, Set<String> collectionKeys, UUID listenId) {
        return byKey.compute(key, (_, existing) -> {
            var group = existing;
            if (group == null) {
                group = new ListenGroup(key, request, collectionKeys,
                        ResultHasher.ordersResults(request.getAggregationSteps()));
                byId.put(group.id(), group);
            }
            group.members().add(listenId);
            return group;
        });
    }

    void leave(UUID groupId, UUID listenId) {
        final var group = byId.get(groupId);
        if (group == null) {
            return;
        }
        byKey.computeIfPresent(group.key(), (_, current) -> {
            current.members().remove(listenId);
            if (!current.members().isEmpty()) {
                return current;
            }
            byId.remove(current.id());
            return null;
        });
    }

    ListenGroup byId(UUID groupId) {
        return byId.get(groupId);
    }

    int size() {
        return byId.size();
    }

    void clear() {
        byKey.clear();
        byId.clear();
    }
}

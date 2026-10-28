package org.techhouse.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class EvictionRanking {
    private static final Comparator<Ranked> ORDER = Comparator.comparingInt(Ranked::tier)
            .thenComparingLong(Ranked::accessCount).thenComparingLong(Ranked::lastAccess);

    private record Ranked(CacheableResource resource, int tier, long accessCount, long lastAccess) {
    }

    private EvictionRanking() {
    }

    static List<CacheableResource> rank(List<CacheableResource> resources, UsageTracker usageTracker) {
        final var ranked = new ArrayList<Ranked>(resources.size());
        for (final var resource : resources) {
            ranked.add(new Ranked(resource, tierOrdinal(resource.kind()), usageTracker.accessCountFor(resource),
                    usageTracker.lastAccessFor(resource)));
        }
        ranked.sort(ORDER);
        final var ordered = new ArrayList<CacheableResource>(ranked.size());
        for (final var entry : ranked) {
            ordered.add(entry.resource());
        }
        return ordered;
    }

    static int tierOrdinal(AccessKind kind) {
        return switch (kind) {
            case COLLECTION -> 0;
            case FIELD_INDEX -> 1;
            case PK_INDEX -> 2;
        };
    }
}

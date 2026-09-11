package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.LongAdder;
import java.util.random.RandomGenerator;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.ioc.IocContainer;

public class ScriptPlacement {
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final LongAdder forwarded = new LongAdder();
    private final LongAdder forwardFallbacks = new LongAdder();
    private final LongAdder localityPreferred = new LongAdder();
    private final RandomGenerator random;

    public ScriptPlacement() {
        this(new Random());
    }

    public ScriptPlacement(RandomGenerator random) {
        this.random = random;
    }

    public NodeInfo choose(String databaseName) {
        if (!clusterConfig.isEnabled() || !clusterConfig.scriptRoutingEnabled()) {
            return null;
        }
        final var self = membershipService.getSelf();
        if (self == null) {
            return null;
        }
        final var eligible = eligibleMembers(self);
        if (eligible.size() <= 1) {
            return null;
        }
        final var chosen = betterOfTwoSamples(eligible, ownershipShares(databaseName));
        return chosen.getNodeId().equals(self.getNodeId()) ? null : chosen;
    }

    public void recordForward() {
        forwarded.increment();
    }

    public void recordFallback() {
        forwardFallbacks.increment();
    }

    public long getForwarded() {
        return forwarded.sum();
    }

    public long getForwardFallbacks() {
        return forwardFallbacks.sum();
    }

    public long getLocalityPreferred() {
        return localityPreferred.sum();
    }

    private List<NodeInfo> eligibleMembers(NodeInfo self) {
        final var selfEpoch = adminEpoch.current();
        final var eligible = new ArrayList<NodeInfo>();
        for (final var member : membershipService.membershipView().aliveMembers()) {
            if (member.getNodeId().equals(self.getNodeId())
                    || (!member.isAdminSyncing() && member.getAdminEpoch() >= selfEpoch)) {
                eligible.add(member);
            }
        }
        return eligible;
    }

    private Map<String, Double> ownershipShares(String databaseName) {
        if (databaseName == null) {
            return Map.of();
        }
        final var collections = cache.getCollectionNamesForDatabase(databaseName);
        if (collections == null || collections.isEmpty()) {
            return Map.of();
        }
        final var counts = new HashMap<String, Integer>();
        for (final var collName : collections) {
            final var owner = ownershipManager.ownerFor(databaseName, collName);
            if (owner != null) {
                counts.merge(owner, 1, Integer::sum);
            }
        }
        final var shares = new HashMap<String, Double>();
        counts.forEach((nodeId, count) -> shares.put(nodeId, (double) count / collections.size()));
        return shares;
    }

    private NodeInfo betterOfTwoSamples(List<NodeInfo> eligible, Map<String, Double> shares) {
        final var size = eligible.size();
        final var first = random.nextInt(size);
        var second = random.nextInt(size - 1);
        if (second >= first) {
            second++;
        }
        return better(eligible.get(first), eligible.get(second), shares, clusterConfig.scriptLocalityWeight());
    }

    private NodeInfo better(NodeInfo a, NodeInfo b, Map<String, Double> shares, int weight) {
        final var winner = blended(a, b, shares, weight);
        final var loadOnly = blended(a, b, shares, 0);
        if (winner != null && (loadOnly == null || !winner.getNodeId().equals(loadOnly.getNodeId()))) {
            localityPreferred.increment();
        }
        // Nothing separates the pair, so the run goes to the first sample rather than to a node-id order.
        // betterOfTwoSamples draws an ordered pair, which makes the first element uniform over the eligible
        // set. That matters because gossip refreshes scriptLoad once per interval and a short script is long
        // over by then: on an idle cluster every pair ties, and a stable node-id order would send every run
        // to the lowest id and never once to the highest. Two nodes sampling the same pair no longer agree,
        // which nothing relies on - each node places only its own runs.
        return winner != null ? winner : a;
    }

    private static NodeInfo blended(NodeInfo a, NodeInfo b, Map<String, Double> shares, int weight) {
        if (isSaturated(a) != isSaturated(b)) {
            return isSaturated(a) ? b : a;
        }
        if (comparable(a) && comparable(b)) {
            final var scoreA = score(a, shares, weight);
            final var scoreB = score(b, shares, weight);
            if (scoreA != scoreB) {
                return scoreA > scoreB ? a : b;
            }
        } else if (a.getScriptLoad() != b.getScriptLoad()) {
            return a.getScriptLoad() < b.getScriptLoad() ? a : b;
        } else {
            final var shareA = share(a, shares);
            final var shareB = share(b, shares);
            if (weight > 0 && shareA != shareB) {
                return shareA > shareB ? a : b;
            }
        }
        return null;
    }

    private static double score(NodeInfo node, Map<String, Double> shares, int weight) {
        return (weight / 100d) * share(node, shares) - loadRatio(node);
    }

    private static double share(NodeInfo node, Map<String, Double> shares) {
        return shares.getOrDefault(node.getNodeId(), 0d);
    }

    private static boolean comparable(NodeInfo node) {
        return node.getScriptCapacity() > 0;
    }

    private static boolean isSaturated(NodeInfo node) {
        return comparable(node) && node.getScriptLoad() >= node.getScriptCapacity();
    }

    private static double loadRatio(NodeInfo node) {
        return (double) node.getScriptLoad() / Math.max(node.getScriptCapacity(), 1);
    }
}

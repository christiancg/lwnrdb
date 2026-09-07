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

/**
 * Picks the node a script runs on, blending availability, current script load and how much of the scoped
 * database a candidate owns.
 *
 * <p>A peer is eligible only if it is {@code ALIVE}, is not still catching up on admin metadata
 * ({@code adminSyncing}) and reports an {@code adminEpoch} at least as high as this node's. Without the last
 * two a script could land on a node that has not applied the DDL the caller is relying on - admin/DDL ops
 * are replicated to a <em>majority</em> and the rest converge through admin anti-entropy - and fail with a
 * transient {@code 404-4}/{@code 404-8} that a local run would not have hit. This node itself is always a
 * candidate: running locally is the fallback in every other case too.
 *
 * <p>The choice is made by <b>power of two choices</b> - sample two distinct live members at random and
 * take the better one. Picking the globally best node instead would herd: every edge sees the same gossiped
 * view, stale by up to one gossip interval, so they would all forward to the same node at once. Sampling
 * needs no accurate global view and still keeps the maximum load exponentially closer to the mean than
 * plain random placement.
 *
 * <p>"Better" is {@code (scriptLocalityWeight / 100) * share - loadRatio}, higher wins, where {@code share}
 * is the fraction of the scoped database's collections the candidate owns on the hash ring and
 * {@code loadRatio} is load <em>relative to</em> {@code maxConcurrentScripts} so a heterogeneous cluster
 * compares like with like. A weight of {@code 0} collapses the score to {@code -loadRatio}, which is exactly
 * the ordering this class had before locality existed; at the default {@code 50} a node owning the whole
 * database beats an idle rival until it is itself more than half full. Locality never overrides saturation:
 * a sample already at its cap loses to one that is not, because it could only answer {@code 503-6}. Nor does
 * it herd - as the owner's load ratio climbs past {@code weight / 100} the score inverts and sampling moves
 * elsewhere. Placing on an owner is what removes round trips: the operations the run issues route themselves,
 * so they resolve locally instead of forwarding.
 *
 * <p>The signal is worth the most for a database with a handful of collections, and nothing for a wide one:
 * with consistent hashing a 50-collection database spreads near-uniformly and every node owns about
 * {@code 1/N} of it either way. That is inherent to a signal that does not know which collections the script
 * will actually touch.
 */
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

    // java.util.Random rather than RandomGenerator.getDefault(): several connection threads sample
    // concurrently and the default implementation is not thread-safe.
    public ScriptPlacement() {
        this(new Random());
    }

    public ScriptPlacement(RandomGenerator random) {
        this.random = random;
    }

    /**
     * @return the node this script should run on, or {@code null} to run it locally (clustering or script
     *         routing off, this node is the only eligible member, or the sampling chose this node).
     */
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

    // Self is kept whatever it reports: it is where the script runs when nothing else qualifies, and the
    // epoch comparison is against its own epoch, so it is trivially caught up with itself.
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

    // Recomputed per placement rather than cached: K ring lookups is microseconds against a script run
    // measured in milliseconds, while a cache would need invalidating on both membership change and DDL.
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

    // Ties break on nodeId so two edges sampling the same pair of idle nodes agree on the answer. The
    // counter is incremented here rather than in choose() so it also counts the run locality kept on the
    // node that received it - the best outcome, and one no other counter can see.
    private NodeInfo better(NodeInfo a, NodeInfo b, Map<String, Double> shares, int weight) {
        final var winner = blended(a, b, shares, weight);
        if (!winner.getNodeId().equals(blended(a, b, shares, 0).getNodeId())) {
            localityPreferred.increment();
        }
        return winner;
    }

    private static NodeInfo blended(NodeInfo a, NodeInfo b, Map<String, Double> shares, int weight) {
        // A saturated target could only answer 503-6, so it loses to any sample that is not itself full,
        // whatever it owns: no amount of locality should route into a rejection.
        if (isSaturated(a) != isSaturated(b)) {
            return isSaturated(a) ? b : a;
        }
        // Load relative to capacity, not absolute: 6/32 is idle where 3/4 is nearly full. A node reporting
        // capacity 0 is either uncapped or too old to gossip the field, so the pair falls back to absolute
        // load rather than comparing against an unknown denominator.
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
        return a.getNodeId().compareTo(b.getNodeId()) <= 0 ? a : b;
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

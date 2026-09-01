package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.LongAdder;
import java.util.random.RandomGenerator;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.ioc.IocContainer;

/**
 * Picks the node a script runs on. Selection is on availability and current script load, not on data
 * locality: a script is scoped to a database but may touch collections owned by several nodes, so there is
 * no owner to route to and the point of placing it is to spread interpreter CPU.
 *
 * <p>A peer is eligible only if it is {@code ALIVE}, is not still catching up on admin metadata
 * ({@code adminSyncing}) and reports an {@code adminEpoch} at least as high as this node's. Without the last
 * two a script could land on a node that has not applied the DDL the caller is relying on - admin/DDL ops
 * are replicated to a <em>majority</em> and the rest converge through admin anti-entropy - and fail with a
 * transient {@code 404-4}/{@code 404-8} that a local run would not have hit. This node itself is always a
 * candidate: running locally is the fallback in every other case too.
 *
 * <p>The choice is made by <b>power of two choices</b> - sample two distinct live members at random and
 * take the less loaded one. Picking the globally least-loaded node instead would herd: every edge sees the
 * same gossiped view, stale by up to one gossip interval, so they would all forward to the same node at
 * once. Sampling needs no accurate global view and still keeps the maximum load exponentially closer to
 * the mean than plain random placement.
 */
public class ScriptPlacement {
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final LongAdder forwarded = new LongAdder();
    private final LongAdder forwardFallbacks = new LongAdder();
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
    public NodeInfo choose() {
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
        final var chosen = lessLoadedOfTwoSamples(eligible);
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

    private NodeInfo lessLoadedOfTwoSamples(List<NodeInfo> eligible) {
        final var size = eligible.size();
        final var first = random.nextInt(size);
        var second = random.nextInt(size - 1);
        if (second >= first) {
            second++;
        }
        return lessLoaded(eligible.get(first), eligible.get(second));
    }

    // Ties break on nodeId so two edges sampling the same pair of idle nodes agree on the answer.
    private static NodeInfo lessLoaded(NodeInfo a, NodeInfo b) {
        if (a.getScriptLoad() != b.getScriptLoad()) {
            return a.getScriptLoad() < b.getScriptLoad() ? a : b;
        }
        return a.getNodeId().compareTo(b.getNodeId()) <= 0 ? a : b;
    }
}

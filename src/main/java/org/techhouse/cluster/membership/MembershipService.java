package org.techhouse.cluster.membership;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.MembershipListener;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ScriptAdmission;
import org.techhouse.ops.ScriptLoad;

public class MembershipService {
    private final Logger logger = Logger.logFor(MembershipService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final ScriptLoad scriptLoad = IocContainer.get(ScriptLoad.class);
    private final ScriptAdmission scriptAdmission = IocContainer.get(ScriptAdmission.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Map<String, NodeInfo> members = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final List<MembershipListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong heartbeatCounter = new AtomicLong();
    private final AtomicBoolean changed = new AtomicBoolean();
    // Pushed in by AdminAntiEntropyService rather than read from it: it already depends on this service, and
    // the IoC container resolves fields during construction, so a field back to it would recurse.
    private volatile boolean adminSyncing;
    private volatile NodeInfo self;
    private ScheduledExecutorService scheduler;

    public void addListener(MembershipListener listener) {
        listeners.add(listener);
    }

    public NodeInfo getSelf() {
        return self;
    }

    public void setAdminSyncing(boolean syncing) {
        adminSyncing = syncing;
    }

    public MembershipView membershipView() {
        return new MembershipView(members.values());
    }

    public void bootstrap(NodeInfo self) {
        this.self = self;
        members.put(self.getNodeId(), self);
        lastSeen.put(self.getNodeId(), System.currentTimeMillis());
        notifyListeners();
    }

    public void start() {
        bootstrap(buildSelf(resolveNodeId()));
        joinSeeds();
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final var thread = new Thread(runnable, "cluster-gossip");
            thread.setDaemon(true);
            return thread;
        });
        final var interval = clusterConfig.gossipIntervalMs();
        scheduler.scheduleAtFixedRate(this::gossipTickSafely, interval, interval, TimeUnit.MILLISECONDS);
        logger.info("Cluster node " + self.getNodeId() + " started on " + self.address());
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        pool.closeAll();
    }

    private void joinSeeds() {
        for (var seed : clusterConfig.seeds()) {
            if (seed.getHost().equals(self.getHost()) && seed.getPort() == self.getPort()) {
                continue;
            }
            try {
                final var response = pool.request(seed, request(ClusterMessageType.JOIN_REQUEST),
                        clusterConfig.replicationAckTimeoutMs());
                mergeAll(response.getMembers());
            } catch (Exception e) {
                logger.warning("Could not join seed " + seed + ": " + e.getMessage());
            }
        }
        maybeNotify();
    }

    public void gossipTickSafely() {
        try {
            gossipTick();
        } catch (Exception e) {
            logger.error("Error during gossip tick", e);
        }
    }

    public void gossipTick() {
        final var now = System.currentTimeMillis();
        self.setHeartbeat(heartbeatCounter.incrementAndGet());
        self.setScriptLoad(scriptLoad.current());
        self.setScriptCapacity(scriptAdmission.capacity());
        self.setAdminSyncing(adminSyncing);
        self.setAdminEpoch(adminEpoch.current());
        lastSeen.put(self.getNodeId(), now);
        for (var member : members.values()) {
            if (member.getNodeId().equals(self.getNodeId()) || member.getState() != NodeState.ALIVE) {
                continue;
            }
            try {
                final var ack = pool.request(member.address(), request(ClusterMessageType.GOSSIP),
                        clusterConfig.replicationAckTimeoutMs());
                mergeAll(ack.getMembers());
            } catch (Exception e) {
                logger.warning("Gossip to " + member.address() + " failed: " + e.getMessage());
            }
        }
        detectFailures(now);
        maybeNotify();
    }

    public ClusterMessage handleJoin(ClusterMessage request) {
        merge(request.getSender());
        maybeNotify();
        return response(ClusterMessageType.JOIN_RESPONSE);
    }

    public ClusterMessage handleGossip(ClusterMessage request) {
        merge(request.getSender());
        mergeAll(request.getMembers());
        maybeNotify();
        return response(ClusterMessageType.GOSSIP_ACK);
    }

    public void detectFailures(long nowMillis) {
        for (var member : members.values()) {
            if (member.getNodeId().equals(self.getNodeId())) {
                continue;
            }
            final var elapsed = nowMillis - lastSeen.getOrDefault(member.getNodeId(), 0L);
            final NodeState newState;
            if (elapsed > clusterConfig.deadTimeoutMs()) {
                newState = NodeState.DEAD;
            } else if (elapsed > clusterConfig.suspectTimeoutMs()) {
                newState = NodeState.SUSPECT;
            } else {
                newState = NodeState.ALIVE;
            }
            if (member.getState() != newState) {
                member.setState(newState);
                changed.set(true);
            }
        }
    }

    private void mergeAll(List<NodeInfo> incoming) {
        if (incoming == null) {
            return;
        }
        for (var node : incoming) {
            merge(node);
        }
    }

    private void merge(NodeInfo incoming) {
        if (incoming == null || incoming.getNodeId() == null || incoming.getNodeId().equals(selfId())) {
            return;
        }
        // compute() runs atomically per key, so concurrent merges on the same node from different
        // connection-handler threads cannot lose an update or move a heartbeat backwards.
        members.compute(incoming.getNodeId(), (id, existing) -> {
            if (existing == null) {
                lastSeen.put(id, System.currentTimeMillis());
                changed.set(true);
                final var joined = new NodeInfo(id, incoming.getHost(), incoming.getPort(), NodeState.ALIVE,
                        incoming.getIncarnation(), incoming.getHeartbeat());
                joined.copyTelemetryFrom(incoming);
                return joined;
            }
            final var fresher = incoming.getIncarnation() > existing.getIncarnation()
                    || (incoming.getIncarnation() == existing.getIncarnation()
                            && incoming.getHeartbeat() > existing.getHeartbeat());
            if (fresher) {
                existing.setIncarnation(incoming.getIncarnation());
                existing.setHeartbeat(incoming.getHeartbeat());
                existing.setHost(incoming.getHost());
                existing.setPort(incoming.getPort());
                // Deliberately not a `changed` event: telemetry moves on every round and firing the
                // membership listeners that often would rebuild the ring and re-run anti-entropy for nothing.
                existing.copyTelemetryFrom(incoming);
                lastSeen.put(id, System.currentTimeMillis());
                if (existing.getState() != NodeState.ALIVE) {
                    existing.setState(NodeState.ALIVE);
                    changed.set(true);
                }
            }
            return existing;
        });
    }

    private String selfId() {
        return self != null ? self.getNodeId() : null;
    }

    private void maybeNotify() {
        if (changed.getAndSet(false)) {
            notifyListeners();
        }
    }

    private void notifyListeners() {
        final var view = membershipView();
        for (var listener : listeners) {
            listener.onMembershipChanged(view);
        }
    }

    private ClusterMessage request(ClusterMessageType type) {
        return new ClusterMessage(null, type, clusterConfig.secret(), self, snapshot());
    }

    private ClusterMessage response(ClusterMessageType type) {
        return new ClusterMessage(null, type, clusterConfig.secret(), self, snapshot());
    }

    private List<NodeInfo> snapshot() {
        return List.copyOf(members.values());
    }

    private NodeInfo buildSelf(String nodeId) {
        return new NodeInfo(nodeId, clusterConfig.advertisedAddress(), clusterConfig.clusterPort(), NodeState.ALIVE,
                System.currentTimeMillis(), 0L, 0, scriptAdmission.capacity());
    }

    private String resolveNodeId() {
        final var configured = clusterConfig.configuredNodeId();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        final var path = nodeIdFilePath();
        try {
            if (Files.exists(path)) {
                final var stored = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!stored.isBlank()) {
                    return stored;
                }
            }
            final var generated = UUID.randomUUID().toString();
            final var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, generated, StandardCharsets.UTF_8);
            return generated;
        } catch (IOException e) {
            logger.warning("Could not persist node id, using an ephemeral one: " + e.getMessage());
            return UUID.randomUUID().toString();
        }
    }

    private Path nodeIdFilePath() {
        return Paths.get(Configuration.getInstance().getFilePath(), Globals.CLUSTER_FOLDER,
                Globals.CLUSTER_NODE_ID_FILE);
    }
}

package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.MetadataCachePruner;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The trigger cache is partitioned by collection ownership: a node keeps only what it owns, because a trigger
 * only ever fires on its collection's owner.
 */
public class MetadataCachePrunerTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final MetadataCachePruner pruner = IocContainer.get(MetadataCachePruner.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clear() {
        cache.removeTriggersMatching(_ -> true);
    }

    private static NodeInfo node(String id) {
        return new NodeInfo(id, "127.0.0.1", 9990, NodeState.ALIVE, 1L, System.currentTimeMillis());
    }

    private void writeTriggers() throws Exception {
        final var definition = new TriggerDefinition("t", java.util.Set.of(EventType.CREATED), "proc",
                TriggerDefinition.MODE_DOCUMENT, false, true, "alice", 1L, 1L, 1L, "alice");
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL,
                eJson.toJson(TriggerDefinition.toFileJson(List.of(definition))));
    }

    // A single-node view owns everything, so nothing is pruned.
    @Test
    public void test_keeps_triggers_for_owned_collections() throws Exception {
        writeTriggers();
        ownershipManager.setSelfNodeId("self");
        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("self"))));
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());

        pruner.onMembershipChanged(new MembershipView(List.of(node("self"))));

        assertEquals(1, cache.metadataCacheStats().triggerEntries());
    }

    // Once this node is not on the ring at all it owns nothing, so every cached list is dropped.
    @Test
    public void test_prunes_triggers_for_collections_no_longer_owned() throws Exception {
        writeTriggers();
        ownershipManager.setSelfNodeId("self");
        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("self"))));
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());

        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("other"))));
        pruner.onMembershipChanged(new MembershipView(List.of(node("other"))));

        assertEquals(0, cache.metadataCacheStats().triggerEntries());
    }

    // Dropped, never blanked: TriggerDispatcher looks the list up again when it runs a queued event, so a
    // pruned collection must reload from its file rather than answer "no triggers".
    @Test
    public void test_pruned_collection_reloads_from_disk() throws Exception {
        writeTriggers();
        ownershipManager.setSelfNodeId("self");
        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("self"))));
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());

        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("other"))));
        pruner.onMembershipChanged(new MembershipView(List.of(node("other"))));

        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());
    }

    @Test
    public void test_malformed_cache_key_is_ignored() {
        cache.putTriggers("nosep", "", List.of());
        ownershipManager.setSelfNodeId("self");
        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("other"))));

        pruner.onMembershipChanged(new MembershipView(List.of(node("other"))));

        assertEquals(1, cache.metadataCacheStats().triggerEntries());
    }
}

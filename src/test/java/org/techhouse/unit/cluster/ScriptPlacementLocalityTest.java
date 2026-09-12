package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ScriptPlacement;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class ScriptPlacementLocalityTest {
    private static final long EPOCH = 42L;
    private static final String DB = "locality_db";
    private static final String UNKNOWN_DB = "no_such_db";
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Configuration config = Configuration.getInstance();
    private ScriptedRandom scriptedRandom;
    private ScriptPlacement placement;
    private StubCache stubCache;
    private StubOwnership stubOwnership;
    private boolean origEnabled;
    private boolean origRouting;
    private int origWeight;
    private long origEpoch;

    private static NodeInfo node(String id, int port, int scriptLoad, int scriptCapacity) {
        final var node = new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L, scriptLoad, scriptCapacity);
        node.setAdminEpoch(EPOCH);
        return node;
    }

    private void membership(NodeInfo self, NodeInfo... others) throws Exception {
        final var members = new LinkedHashMap<String, NodeInfo>();
        members.put(self.getNodeId(), self);
        for (final var other : others) {
            members.put(other.getNodeId(), other);
        }
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
    }

    // The database has four collections so a share can be 1, 0 or a half without rounding.
    private void ownedEntirelyBy(String nodeId) {
        stubCache.collections = List.of("c1", "c2", "c3", "c4");
        final var owners = new HashMap<String, String>();
        stubCache.collections.forEach(coll -> owners.put(coll, nodeId));
        stubOwnership.owners = owners;
    }

    private void ownedHalfEach() {
        stubCache.collections = List.of("c1", "c2", "c3", "c4");
        stubOwnership.owners = Map.of("c1", "b", "c2", "b", "c3", "c", "c4", "c");
    }

    private void weight(int value) throws Exception {
        TestUtils.setPrivateField(config, "scriptLocalityWeight", value);
    }

    @BeforeEach
    public void setUp() throws Exception {
        scriptedRandom = new ScriptedRandom();
        placement = new ScriptPlacement(scriptedRandom);
        stubCache = new StubCache();
        stubOwnership = new StubOwnership();
        TestUtils.setPrivateField(placement, "cache", stubCache);
        TestUtils.setPrivateField(placement, "ownershipManager", stubOwnership);
        origEnabled = config.isClusterEnabled();
        origRouting = config.isScriptRoutingEnabled();
        origWeight = config.getScriptLocalityWeight();
        origEpoch = adminEpoch.current();
        TestUtils.setPrivateField(adminEpoch, "epoch", EPOCH);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", true);
        weight(50);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", origRouting);
        TestUtils.setPrivateField(config, "scriptLocalityWeight", origWeight);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.setPrivateField(adminEpoch, "epoch", origEpoch);
    }

    // The equivalence claim the whole feature rests on: the fixture and expected winner of
    // ScriptPlacementTest.test_picks_the_less_loaded_of_two_samples, with b owning the entire database.
    @Test
    public void test_weight_zero_reproduces_load_only_ordering() throws Exception {
        weight(0);
        membership(node("a-self", 1, 9, 0), node("b", 2, 7, 0), node("c", 3, 2, 0));
        ownedEntirelyBy("b");
        scriptedRandom.give(1, 1);
        assertEquals("c", placement.choose(DB).getNodeId());
        assertEquals(0L, placement.getLocalityPreferred());
    }

    @Test
    public void test_owner_of_the_whole_database_beats_an_idle_peer() throws Exception {
        membership(node("a-self", 1, 9, 10), node("b", 2, 4, 10), node("c", 3, 0, 10));
        ownedEntirelyBy("b");
        scriptedRandom.give(1, 1);
        assertEquals("b", placement.choose(DB).getNodeId(), "0.5 * 1 - 0.4 beats 0 - 0");
    }

    @Test
    public void test_load_overrides_locality_past_the_weight() throws Exception {
        membership(node("a-self", 1, 9, 10), node("b", 2, 6, 10), node("c", 3, 0, 10));
        ownedEntirelyBy("b");
        scriptedRandom.give(1, 1);
        assertEquals("c", placement.choose(DB).getNodeId(), "0.5 * 1 - 0.6 loses to 0 - 0");
    }

    @Test
    public void test_saturated_owner_loses_to_an_unsaturated_non_owner() throws Exception {
        weight(100);
        membership(node("a-self", 1, 9, 10), node("b", 2, 10, 10), node("c", 3, 9, 10));
        ownedEntirelyBy("b");
        scriptedRandom.give(1, 1);
        assertEquals("c", placement.choose(DB).getNodeId());
    }

    @Test
    public void test_empty_database_falls_back_to_load() throws Exception {
        weight(100);
        membership(node("a-self", 1, 9, 10), node("b", 2, 4, 10), node("c", 3, 0, 10));
        stubCache.collections = List.of();
        stubOwnership.owners = Map.of("c1", "b");
        scriptedRandom.give(1, 1);
        assertEquals("c", placement.choose(DB).getNodeId());
        assertEquals(0L, placement.getLocalityPreferred());
    }

    @Test
    public void test_unknown_database_falls_back_to_load() throws Exception {
        weight(100);
        membership(node("a-self", 1, 9, 10), node("b", 2, 4, 10), node("c", 3, 0, 10));
        ownedEntirelyBy("b");
        scriptedRandom.give(1, 1);
        assertEquals("c", placement.choose(UNKNOWN_DB).getNodeId(), "a database this node knows nothing about");
        scriptedRandom.give(1, 1);
        assertEquals("c", placement.choose(null).getNodeId(), "an operation carrying no database at all");
        assertEquals(0L, placement.getLocalityPreferred());
    }

    @Test
    public void test_uncapped_node_pair_uses_absolute_load_then_share() throws Exception {
        membership(node("a-self", 1, 9, 0), node("b", 2, 3, 0), node("c", 3, 5, 0));
        ownedEntirelyBy("c");
        scriptedRandom.give(1, 1);
        assertEquals("b", placement.choose(DB).getNodeId(), "share must not override an unequal absolute load");

        membership(node("a-self", 1, 9, 0), node("b", 2, 3, 0), node("c", 3, 3, 0));
        ownedEntirelyBy("c");
        scriptedRandom.give(1, 1);
        assertEquals("c", placement.choose(DB).getNodeId(), "equal loads: share wins over the draw order");
    }

    @Test
    public void test_share_tie_breaks_on_the_first_sample() throws Exception {
        membership(node("a-self", 1, 9, 10), node("c", 2, 4, 10), node("b", 3, 4, 10));
        ownedHalfEach();
        scriptedRandom.give(1, 1);
        assertEquals("c", placement.choose(DB).getNodeId());
    }

    @Test
    public void test_locality_preferred_counter_increments_only_when_locality_changed_the_winner() throws Exception {
        membership(node("a-self", 1, 9, 10), node("b", 2, 4, 10), node("c", 3, 0, 10));
        ownedEntirelyBy("b");
        scriptedRandom.give(1, 1);
        assertEquals("b", placement.choose(DB).getNodeId());
        assertEquals(1L, placement.getLocalityPreferred());

        ownedEntirelyBy("c");
        scriptedRandom.give(1, 1);
        assertEquals("c", placement.choose(DB).getNodeId(), "locality agrees with load, so it changed nothing");
        assertEquals(1L, placement.getLocalityPreferred());
    }

    @Test
    public void test_self_wins_on_locality_returns_null() throws Exception {
        membership(node("a-self", 1, 4, 10), node("b", 2, 0, 10), node("c", 3, 0, 10));
        ownedEntirelyBy("a-self");
        scriptedRandom.give(0, 0);
        assertNull(placement.choose(DB));
        assertEquals(1L, placement.getLocalityPreferred());
    }

    @Test
    public void test_weight_one_hundred_lets_an_owner_win_below_full_load() throws Exception {
        weight(100);
        membership(node("a-self", 1, 9, 10), node("b", 2, 9, 10), node("c", 3, 0, 10));
        ownedEntirelyBy("b");
        scriptedRandom.give(1, 1);
        assertEquals("b", placement.choose(DB).getNodeId(), "1 * 1 - 0.9 still beats 0 - 0");
    }

    @Test
    public void test_concurrent_placement_is_safe() throws Exception {
        membership(node("a-self", 1, 9, 10), node("b", 2, 4, 10), node("c", 3, 0, 10));
        ownedEntirelyBy("b");
        final var concurrent = new ScriptPlacement(new FixedRandom());
        TestUtils.setPrivateField(concurrent, "cache", stubCache);
        TestUtils.setPrivateField(concurrent, "ownershipManager", stubOwnership);
        final var threads = 8;
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(threads);
        final var owners = new AtomicInteger();
        for (var i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (var run = 0; run < 200; run++) {
                        final var chosen = concurrent.choose(DB);
                        if (chosen != null && "b".equals(chosen.getNodeId())) {
                            owners.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(threads * 200, owners.get());
        assertEquals((long) threads * 200, concurrent.getLocalityPreferred());
    }

    @Test
    public void test_counter_starts_at_zero() {
        assertEquals(0L, placement.getLocalityPreferred());
    }

    private static final class StubCache extends Cache {
        private List<String> collections = List.of();

        @Override
        public List<String> getCollectionNamesForDatabase(String dbName) {
            return DB.equals(dbName) ? collections : null;
        }
    }

    private static final class StubOwnership extends OwnershipManager {
        private Map<String, String> owners = Map.of();

        @Override
        public String ownerFor(String dbName, String collName) {
            return owners.get(collName);
        }
    }

    private static final class ScriptedRandom implements RandomGenerator {
        private int[] values = new int[0];
        private int index;

        private void give(int... samples) {
            values = samples;
            index = 0;
        }

        @Override
        public int nextInt(int bound) {
            return values[index++];
        }

        @Override
        public long nextLong() {
            return 0L;
        }
    }

    private static final class FixedRandom implements RandomGenerator {
        @Override
        public int nextInt(int bound) {
            return 1;
        }

        @Override
        public long nextLong() {
            return 0L;
        }
    }
}

package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TombstoneOnMissingDeleteTest {
    private final Configuration config = Configuration.getInstance();
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private boolean origEnabled;
    private int origExpected;

    private static NodeInfo node() {
        return new NodeInfo("self", "127.0.0.1", 5000, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>(Map.of("self", node())));
        TestUtils.setPrivateField(membershipService, "self", node());
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(membershipService.membershipView());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save() {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("present"));
        request.setObject(obj);
        request.set_id("present");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private OperationStatus delete(String id) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    @Test
    public void test_delete_of_a_missing_id_writes_no_tombstone() throws Exception {
        save();

        assertEquals(OperationStatus.NOT_FOUND, delete("never-existed"));

        assertTrue(fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_delete_of_a_present_id_still_writes_its_tombstone() throws Exception {
        save();

        assertEquals(OperationStatus.OK, delete("present"));

        final var tombstones = fs.readTombstones(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, tombstones.size());
        assertTrue(tombstones.containsKey("present"));
    }
}

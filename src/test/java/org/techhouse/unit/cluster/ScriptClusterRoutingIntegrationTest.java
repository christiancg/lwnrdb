package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScriptClusterRoutingIntegrationTest extends ScriptClusterTestBase {
    // A cross-owner script transaction (local slice + a remote participant) commits through 2PC
    @Test
    public void test_cross_owner_transaction_commits_via_two_phase_commit() throws Exception {
        configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var remote = collectionOwnedByOther();
        createCollection(remote);
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.beginTransaction();
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("local-part"));
        db.save(TestGlobals.DB, remote, doc("remote-part"));
        db.commitTransaction();
        assertEquals(OperationStatus.OK, findStatus(TestGlobals.COLL, "local-part"));
        assertEquals(OperationStatus.OK, findStatus(remote, "remote-part"));
    }

    // A script transaction that rolls back aborts the remote participant's slice too
    @Test
    public void test_script_transaction_rollback_aborts_remote_participant() throws Exception {
        configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var remote = collectionOwnedByOther();
        createCollection(remote);
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.beginTransaction();
        db.save(TestGlobals.DB, remote, doc("rolled-back"));
        db.rollbackTransaction();
        assertEquals(OperationStatus.NOT_FOUND, findStatus(remote, "rolled-back"));
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }

    // An unreachable owner surfaces into the script rather than reading as a silent no-op
    @Test
    public void test_owner_unreachable_surfaces_as_script_error() throws Exception {
        configureMembership(2, node("self", 19990), node("other", 1));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertThrows(JsThrowException.class, () -> db.save(TestGlobals.DB, coll, doc("unreachable")));
        assertThrows(JsThrowException.class, () -> db.bulkSave(TestGlobals.DB, coll, List.of(doc("unreachable-2"))));
        assertThrows(JsThrowException.class, () -> db.delete(TestGlobals.DB, coll, "unreachable"));
    }

    // With clustering disabled every dispatch runs locally, exactly as before
    @Test
    public void test_cluster_disabled_runs_everything_locally() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("standalone"));
        assertEquals(OperationStatus.OK, findStatus(TestGlobals.COLL, "standalone"));
        assertNotNull(db.findById(TestGlobals.DB, TestGlobals.COLL, "standalone"));
    }

    // A collection this node owns is still written locally, with no forwarding
    @Test
    public void test_owned_collection_write_stays_local() throws Exception {
        configureMembership(1, node("self", cluster.serverPort()));
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("owned"));
        assertEquals(OperationStatus.OK, findStatus(TestGlobals.COLL, "owned"));
    }

    // Session teardown clears the cluster transaction state, so a later transaction on the same
    // caller-supplied client never 2PCs against stale participants.
    @Test
    public void test_session_teardown_clears_cluster_transaction_state() throws Exception {
        configureMembership(1, node("self", cluster.serverPort()));
        final var clientId = clientTracker.registerForwardedClient(ADMIN);
        try {
            final var db = new EnforcingDatabaseAccess(ADMIN, clientId);
            db.beginTransaction();
            try {
                db.save(TestGlobals.DB, TestGlobals.COLL, doc("cluster-state"));
                assertTrue(clientTracker.hasLocalSlice(clientId));
            } finally {
                db.commitTransaction();
            }
            assertFalse(clientTracker.hasLocalSlice(clientId));
            assertTrue(clientTracker.transactionParticipants(clientId).isEmpty());
        } finally {
            clientTracker.removeById(clientId);
        }
    }

    // Routing off keeps the pre-existing behaviour: the script runs on the node that received it.
    @Test
    public void test_run_script_stays_local_when_routing_is_disabled() throws Exception {
        enableScriptRouting();
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", false);
        final var raw = "{\"type\":\"RUN_SCRIPT\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"script\":\"return 1;\"}";
        assertNull(router.forward(RequestParser.parseRequest(raw), raw, false, ADMIN, null));
    }

    // Placement blends how much of the scoped database a node owns with its load, so a run whose loads tie
    // goes to the owner - which is what makes the operations it issues resolve without a round trip.
    @Test
    public void test_run_script_is_placed_on_the_owner_of_the_scoped_database() throws Exception {
        TestUtils.setPrivateField(config, "scriptsEnabled", true);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", true);
        TestUtils.setPrivateField(config, "scriptLocalityWeight", 100);
        configureMembershipWithAPeerOwningTheWholeDatabase();
        final var raw = "{\"type\":\"RUN_SCRIPT\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"script\":\"return 41 + 1;\"}";
        final var forwardedBefore = scriptPlacement.getForwarded();
        final var preferredBefore = scriptPlacement.getLocalityPreferred();
        final var response = router.forward(RequestParser.parseRequest(raw), raw, false, ADMIN, null);
        assertNotNull(response, "locality should have moved the run to the owner, not kept it here");
        assertTrue(response.contains("\"result\":42"), response);
        assertEquals(forwardedBefore + 1, scriptPlacement.getForwarded());
        assertTrue(scriptPlacement.getLocalityPreferred() > preferredBefore);
    }

    // The equivalence claim: the same fixture at weight 0 falls back to the nodeId tiebreak, which keeps the
    // run here, and locality is recorded as having changed nothing.
    @Test
    public void test_locality_weight_zero_leaves_placement_load_only() throws Exception {
        TestUtils.setPrivateField(config, "scriptsEnabled", true);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", true);
        configureMembershipWithAPeerOwningTheWholeDatabase();
        TestUtils.setPrivateField(config, "scriptLocalityWeight", 0);
        final var raw = "{\"type\":\"RUN_SCRIPT\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"script\":\"return 41 + 1;\"}";
        final var forwardedBefore = scriptPlacement.getForwarded();
        final var preferredBefore = scriptPlacement.getLocalityPreferred();
        assertNull(router.forward(RequestParser.parseRequest(raw), raw, false, ADMIN, null));
        assertEquals(forwardedBefore, scriptPlacement.getForwarded());
        assertEquals(preferredBefore, scriptPlacement.getLocalityPreferred());
    }
}

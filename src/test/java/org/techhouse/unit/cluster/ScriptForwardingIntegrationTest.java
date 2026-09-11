package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.ScriptAdmission;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.ResponseParser;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.test.TestGlobals;

public class ScriptForwardingIntegrationTest extends ScriptClusterTestBase {
    private String collectionOwnedBySelf() {
        for (var i = 0; i < 500; i++) {
            final var coll = "script-local-" + i;
            if (ownership.isOwner(TestGlobals.DB, coll)) {
                return coll;
            }
        }
        throw new IllegalStateException("no collection owned by this node");
    }

    // Seeded through the replica-apply path: a direct save would be refused by the ownership guard,
    // since in a single JVM the "owner" node shares this node's OwnershipManager.
    private void seed(String coll, String id) {
        ReplicatedApplyHelper
                .apply(new ReplicationPayload(TestGlobals.DB, coll, ReplicationOp.UPSERT, List.of(doc(id)), null));
    }

    // A non-transactional script read of a collection this node does not own is forwarded to the owner
    @Test
    public void test_non_transactional_read_of_foreign_collection_is_forwarded() throws Exception {
        configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        seed(coll, "routed-read");
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        final var found = db.findById(TestGlobals.DB, coll, "routed-read");
        assertNotNull(found);
        assertEquals("hello", found.get("value").asJsonString().getValue());
    }

    // A non-transactional script write now takes the routing path: an unreachable owner yields
    // 503-4 OWNER_UNREACHABLE, an error only ClusterRouter produces, where the unrouted path would
    // have been rejected locally with 421-1 NOT_COLLECTION_OWNER.
    @Test
    public void test_non_transactional_write_takes_the_routing_path() throws Exception {
        configureMembership(2, node("self", 19990), node("other", 1));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        final var error = assertThrows(JsThrowException.class,
                () -> db.bulkSave(TestGlobals.DB, coll, List.of(doc("routed-write"))));
        final var message = ((JsObject) error.getValue()).get("message");
        assertTrue(JsCoercion.toStr(message).contains("unreachable"),
                "expected an OWNER_UNREACHABLE message, got: " + JsCoercion.toStr(message));
    }

    // A forwarded aggregate ships the caller's own pipeline JSON and returns the owner's results
    @Test
    public void test_aggregate_on_foreign_collection_is_forwarded() throws Exception {
        configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        seed(coll, "agg-1");
        final var db = new EnforcingDatabaseAccess(ADMIN, null);

        final var operator = new JsonObject();
        operator.add("fieldOperatorType", new JsonString("EQUALS"));
        operator.add("field", new JsonString("value"));
        operator.add("value", new JsonString("hello"));
        final var step = new JsonObject();
        step.add("type", new JsonString("FILTER"));
        step.add("operator", operator);
        final var pipeline = new JsonArray();
        pipeline.add(step);

        final var results = db.aggregate(TestGlobals.DB, coll, pipeline);
        assertEquals(1, results.size());
        assertEquals("agg-1", results.getFirst().get("_id").asJsonString().getValue());
    }

    // A transactional write to a foreign collection registers its owner as a 2PC participant
    @Test
    public void test_transactional_write_to_foreign_collection_becomes_participant() throws Exception {
        configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.beginTransaction();
        try {
            db.save(TestGlobals.DB, coll, doc("participant"));
            // The owner opened a persistent TxSession, which only happens on a FORWARD_TX_REQUEST.
            assertFalse(clientTracker.txSessionsSnapshot().isEmpty());
        } finally {
            db.rollbackTransaction();
        }
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }

    // A read inside a transaction is forwarded only to an owner already holding a slice
    @Test
    public void test_read_inside_transaction_forwards_only_to_an_existing_participant() throws Exception {
        configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var remote = collectionOwnedByOther();
        createCollection(remote);
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.beginTransaction();
        try {
            // No slice yet: the read runs locally and finds nothing.
            assertNull(db.findById(TestGlobals.DB, remote, "read-yw"));
            db.save(TestGlobals.DB, remote, doc("read-yw"));
            // Now the owner is a participant, so the read is forwarded and sees the buffered write.
            assertNotNull(db.findById(TestGlobals.DB, remote, "read-yw"));
        } finally {
            db.rollbackTransaction();
        }
    }

    // A forwarded aggregate ships the caller's own pipeline JSON verbatim, so a CUSTOM (geo) operator
    // survives the round trip - re-serializing the parsed operator tree would mangle it.
    @Test
    public void test_forwarded_aggregate_preserves_a_custom_geo_operator() throws Exception {
        configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        final var located = new JsonObject();
        located.add("_id", new JsonString("geo-1"));
        located.add("location", new JsonGeo("#geo(40.0,-74.0)"));
        ReplicatedApplyHelper
                .apply(new ReplicationPayload(TestGlobals.DB, coll, ReplicationOp.UPSERT, List.of(located), null));

        final var operator = new JsonObject();
        operator.add("customOperatorName", new JsonString("distance"));
        operator.add("field", new JsonString("location"));
        operator.add("value", new JsonGeo("#geo(40.0,-74.0)"));
        operator.add("comparator", new JsonString("SMALLER_THAN"));
        operator.add("distance", new JsonNumber((double) 1000));
        final var step = new JsonObject();
        step.add("type", new JsonString("FILTER"));
        step.add("operator", operator);
        final var pipeline = new JsonArray();
        pipeline.add(step);

        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        final var results = db.aggregate(TestGlobals.DB, coll, pipeline);
        assertEquals(1, results.size());
        assertEquals("geo-1", results.getFirst().get("_id").asJsonString().getValue());
    }

    // A whole RUN_SCRIPT is placed on the less loaded node and the target's response JSON is relayed
    // verbatim. The peer here is this same JVM's cluster server, which runs the script through
    // handleForward -> OperationProcessor, bypassing the router, so no forwarding loop is possible.
    @Test
    public void test_run_script_is_forwarded_to_the_chosen_node_and_the_response_relayed() throws Exception {
        enableScriptRouting();
        final var raw = "{\"type\":\"RUN_SCRIPT\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"script\":\"return 41 + 1;\"}";
        final var forwardedBefore = scriptPlacement.getForwarded();
        final var response = router.forward(RequestParser.parseRequest(raw), raw, false, ADMIN, null);
        assertNotNull(response, "the script should have been forwarded, not run locally");
        assertTrue(response.contains("\"result\":42"), response);
        assertEquals(forwardedBefore + 1, scriptPlacement.getForwarded());
    }

    // The acting user travels with the forward: a script running with no user could not read at all,
    // since EnforcingDatabaseAccess resolves the caller's record on every operation.
    @Test
    public void test_forwarded_script_runs_as_the_acting_user() throws Exception {
        enableScriptRouting();
        // A collection the receiving node owns, so the script's own read stays local on the target: in a
        // single JVM both "nodes" share one connection pool, and a nested hop back would wait on the
        // connection the outer forward is already using.
        final var coll = collectionOwnedBySelf();
        createCollection(coll);
        seed(coll, "forwarded-read");
        final var script = "import db from \\\"db\\\"; return db.findById(db.name, \\\"" + coll
                + "\\\", \\\"forwarded-read\\\").value;";
        final var raw = "{\"type\":\"RUN_SCRIPT\",\"databaseName\":\"" + TestGlobals.DB + "\",\"script\":\"" + script
                + "\"}";
        final var response = router.forward(RequestParser.parseRequest(raw), raw, false, ADMIN, null);
        assertNotNull(response, "the script should have been forwarded, not run locally");
        assertTrue(response.contains("hello"), response);
    }

    // A 503-6 is a real response, so it is relayed rather than retried here. forwardScript's fallback is
    // deliberately generous - a transport failure runs the script locally - and falling back on a capacity
    // rejection would let the cluster route around the very cap protecting the target node.
    @Test
    public void test_forwarded_script_rejection_is_relayed_not_retried_locally() throws Exception {
        enableScriptRouting();
        final var admission = IocContainer.get(ScriptAdmission.class);
        admission.reconfigure(1, 0L);
        final var raw = "{\"type\":\"RUN_SCRIPT\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"script\":\"return 41 + 1;\"}";
        final var forwardedBefore = scriptPlacement.getForwarded();
        final var fallbacksBefore = scriptPlacement.getForwardFallbacks();
        assertTrue(admission.tryAcquire());
        final String response;
        try {
            response = router.forward(RequestParser.parseRequest(raw), raw, false, ADMIN, null);
        } finally {
            admission.release();
            admission.reconfigure(0, 0L);
        }
        assertNotNull(response, "the target's rejection must be relayed, not retried locally");
        assertTrue(response.contains(ErrorCode.SCRIPT_CONCURRENCY_LIMIT.getCode()), response);
        assertFalse(response.contains("\"result\":42"), "the script ran anyway: " + response);
        assertEquals(forwardedBefore + 1, scriptPlacement.getForwarded());
        assertEquals(fallbacksBefore, scriptPlacement.getForwardFallbacks());
    }

    // An unreadable response from the owner becomes a script-visible error, not a raw exception
    @Test
    public void test_unreadable_owner_response_becomes_a_script_error() {
        assertThrows(RuntimeException.class, () -> ResponseParser.parseResponse("not json"));
    }
}

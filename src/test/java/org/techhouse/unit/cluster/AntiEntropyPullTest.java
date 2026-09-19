package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AntiEntropyService;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AntiEntropyPullTest {
    private final AntiEntropyService service = IocContainer.get(AntiEntropyService.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save(String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.addProperty("payload", id + "-value");
        request.setObject(object);
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private PkIndexEntry byId(String id) throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(e -> e.getValue().equals(id)).findFirst().orElseThrow();
    }

    @Test
    public void test_pull_answers_each_requested_id_with_its_own_document() throws Exception {
        save("a");
        save("b");

        final var pull = service.buildPull(TestGlobals.DB, TestGlobals.COLL, List.of("a", "b"));

        assertEquals(2, pull.getDocuments().size());
        for (final var document : pull.getDocuments()) {
            final var id = document.get(Globals.PK_FIELD).asJsonString().getValue();
            assertEquals(id + "-value", document.get("payload").asJsonString().getValue(),
                    "a pulled document must be the one its id names");
        }
    }

    @Test
    public void test_pull_never_answers_with_a_different_document() throws Exception {
        save("a");
        save("b");
        final var target = byId("a");
        final var other = byId("b");
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "a");
        target.setPosition(other.getPosition());
        target.setLength(other.getLength());
        target.setPage(other.getPage());

        assertThrows(Exception.class, () -> service.buildPull(TestGlobals.DB, TestGlobals.COLL, List.of("a")),
                "a stale offset must fail the pull rather than ship another document's body to a peer");
    }
}

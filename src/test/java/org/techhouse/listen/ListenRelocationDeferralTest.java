package org.techhouse.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenRelocationDeferralTest {
    private static final String COLL = "relocatingColl";
    private static final String KEY = Cache.getCollectionIdentifier(TestGlobals.DB, COLL);

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final Configuration config = Configuration.getInstance();
    private final List<Boolean> heldBackAtEachEvent = new ArrayList<>();
    private BackgroundTaskManager originalTaskManager;
    private Long originalMaxPageSize;
    private Long originalMaxEntrySize;
    private EventType failOn;

    private final class RecordingTaskManager extends BackgroundTaskManager {
        @Override
        public void submitBackgroundTask(Event op) {
            if (op instanceof EntityEvent entityEvent && entityEvent.getCollName().equals(COLL)) {
                heldBackAtEachEvent.add(listenManager.applySnapshot(Set.of(KEY)) == null);
                if (entityEvent.getType() == failOn) {
                    throw new IllegalStateException("simulated failure mid-relocation");
                }
            }
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalTaskManager = TestUtils.getPrivateStaticField(SaveOperationHelper.class, "taskManager",
                BackgroundTaskManager.class);
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", new RecordingTaskManager());
        originalMaxPageSize = config.getMaxPageSize();
        originalMaxEntrySize = config.getMaxEntrySize();
        TestUtils.setPrivateField(config, "maxPageSize", 2000L);
        TestUtils.setPrivateField(config, "maxEntrySize", 100_000L);
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, COLL)).getStatus());
        save("keep", "k".repeat(280));
        save("a", "small");
        heldBackAtEachEvent.clear();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", originalTaskManager);
        TestUtils.setPrivateField(config, "maxPageSize", originalMaxPageSize);
        TestUtils.setPrivateField(config, "maxEntrySize", originalMaxEntrySize);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static SaveRequest saveOf(String id, String value) {
        final var request = new SaveRequest(TestGlobals.DB, COLL);
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("v", new JsonString(value));
        request.setObject(object);
        request.set_id(id);
        return request;
    }

    private void save(String id, String value) {
        assertEquals(OperationStatus.OK, processor.processMessage(saveOf(id, value)).getStatus());
    }

    @Test
    public void test_a_relocating_save_holds_listens_back_for_the_whole_move() {
        save("a", "x".repeat(1780));

        assertEquals(List.of(true, true), heldBackAtEachEvent,
                "between the delete and the insert a lock-free listen re-run saw the document missing and pushed it");
        assertNotNull(listenManager.applySnapshot(Set.of(KEY)), "the hold must end with the save");
    }

    @Test
    public void test_an_in_place_save_does_not_hold_listens_back() {
        save("keep", "k".repeat(200));

        assertEquals(List.of(false), heldBackAtEachEvent);
    }

    @Test
    public void test_a_relocation_that_fails_still_releases_the_hold() {
        failOn = EventType.DELETED;

        final var response = processor.processMessage(saveOf("a", "x".repeat(1780)));

        assertNotEquals(OperationStatus.OK, response.getStatus());
        assertTrue(heldBackAtEachEvent.getFirst());
        assertNotNull(listenManager.applySnapshot(Set.of(KEY)), "a failed relocation left every listen held back");
    }
}

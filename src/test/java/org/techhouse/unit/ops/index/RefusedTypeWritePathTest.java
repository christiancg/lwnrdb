package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EntityRef;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.index.IndexedValue;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestUtils;

public class RefusedTypeWritePathTest {
    private static final String DB = FieldIndexBatchSupport.DB;
    private static final String COLL = FieldIndexBatchSupport.COLL;
    private static final String FIELD = "f";
    private static final String SLOT_CLASS = "org.techhouse.ops.index.FieldIndexSlot";

    private final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final BackgroundTaskManager taskManager = mock(BackgroundTaskManager.class);
    private BackgroundTaskManager saveTaskManager;
    private BackgroundTaskManager deleteTaskManager;
    private Long savedMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        saveTaskManager = TestUtils.getPrivateStaticField(SaveOperationHelper.class, "taskManager",
                BackgroundTaskManager.class);
        deleteTaskManager = TestUtils.getPrivateStaticField(DeleteOperationHelper.class, "taskManager",
                BackgroundTaskManager.class);
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", taskManager);
        TestUtils.setPrivateStaticField(DeleteOperationHelper.class, "taskManager", taskManager);
        IndexHelper.createIndex(DB, COLL, FIELD);
        cache.getAdminCollectionEntry(DB, COLL).setIndexes(Set.of(FIELD));
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", Globals.CACHE_DISABLED);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", realFs);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", saveTaskManager);
        TestUtils.setPrivateStaticField(DeleteOperationHelper.class, "taskManager", deleteTaskManager);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, String value) {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        if (value != null) {
            object.addProperty(FIELD, value);
        }
        return object;
    }

    private void save(String id, String value) throws Exception {
        final var request = new SaveRequest(DB, COLL);
        request.setObject(document(id, value));
        locks.lock(DB, COLL);
        try {
            SaveOperationHelper.executeSave(request);
        } finally {
            locks.release(DB, COLL);
        }
    }

    private void delete(String id) throws Exception {
        final var request = new DeleteRequest(DB, COLL);
        request.set_id(id);
        locks.lock(DB, COLL);
        try {
            DeleteOperationHelper.executeDelete(request);
        } finally {
            locks.release(DB, COLL);
        }
    }

    private void bulkSave(JsonObject... objects) throws Exception {
        final var request = new BulkSaveRequest(DB, COLL);
        request.setObjects(new ArrayList<>(List.of(objects)));
        locks.lock(DB, COLL);
        try {
            SaveOperationHelper.executeBulkSave(request);
        } finally {
            locks.release(DB, COLL);
        }
    }

    private void bulkDelete(String... ids) throws Exception {
        locks.lock(DB, COLL);
        try {
            DeleteOperationHelper.executeBulkDelete(DB, COLL, List.of(ids), (_, _) -> {
            }, true);
        } finally {
            locks.release(DB, COLL);
        }
    }

    private List<Event> submitted() {
        final var captor = ArgumentCaptor.forClass(Event.class);
        verify(taskManager, atLeast(0)).submitBackgroundTask(captor.capture());
        clearInvocations(taskManager);
        return new ArrayList<>(captor.getAllValues());
    }

    private List<DbEntry> storedDocuments() throws Exception {
        return realFs.getByIndexEntries(realFs.readWholePkIndexFile(DB, COLL));
    }

    private void assertIndexMatchesTheDocuments() throws Exception {
        assertEquals(FieldIndexBatchSupport.legacyBuildSnapshot(storedDocuments(), FIELD),
                FieldIndexBatchSupport.snapshot(FIELD));
    }

    private static List<EntityRef> refsOf(Event event) {
        return switch (event) {
            case EntityEvent entity -> List.of(entity.getEntity());
            case BulkEntityEvent bulk -> {
                final var refs = new ArrayList<>(bulk.getInserted());
                refs.addAll(bulk.getUpdated());
                yield refs;
            }
            default -> List.of();
        };
    }

    @Test
    public void everyWritePathCarriesTheValueItReplaced() throws Exception {
        save("a", "one");
        bulkSave(document("b", "two"), document("c", "three"));
        EventProcessorHelper.processBatch(submitted());

        save("a", "uno");
        delete("b");
        bulkSave(document("c", "tres"), document("d", "four"));
        bulkDelete("d", "missing");
        final var events = submitted();

        final var priors = new ArrayList<IndexedValue>();
        for (final var event : events) {
            for (final var ref : refsOf(event)) {
                assertNotNull(ref.priorIndexed(), ref.id() + " carries no prior values");
                assertTrue(ref.priorIndexed().containsKey(FIELD));
                priors.add(ref.priorIndexed().get(FIELD));
            }
        }
        assertEquals(List.of("four", "one", "three", "two"),
                priors.stream().filter(Objects::nonNull).map(prior -> (String) prior.value()).sorted().toList());
        EventProcessorHelper.processBatch(events);
        assertIndexMatchesTheDocuments();
    }

    @Test
    public void eventsProcessedInAnyOrderLeaveTheIndexEqualToTheDocuments() throws Exception {
        final var random = new Random(20261030L);
        for (var i = 0; i < 20; i++) {
            save("id" + i, "v" + random.nextInt(5));
        }
        EventProcessorHelper.processBatch(submitted());
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", spied);
        for (var round = 0; round < 10; round++) {
            for (var write = 0; write < 6; write++) {
                final var id = "id" + random.nextInt(25);
                switch (random.nextInt(4)) {
                    case 0 -> delete(id);
                    case 1 ->
                        bulkSave(document(id, "w" + random.nextInt(5)), document("id" + random.nextInt(25), null));
                    case 2 -> bulkDelete(id, "id" + random.nextInt(25));
                    default -> save(id, "v" + random.nextInt(5));
                }
            }
            final var events = submitted();
            Collections.shuffle(events, random);
            for (final var event : events) {
                EventProcessorHelper.processEvent(event);
            }
            assertIndexMatchesTheDocuments();
        }

        verify(spied, never()).readWholeFieldIndexFiles(anyString(), anyString(), eq(FIELD), eq(String.class));
    }

    @Test
    public void aCollectionWithoutRefusedTypesCarriesNoPriors() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        save("a", "one");
        save("a", "two");

        for (final var event : submitted()) {
            for (final var ref : refsOf(event)) {
                assertNull(ref.priorIndexed());
            }
        }
    }
}

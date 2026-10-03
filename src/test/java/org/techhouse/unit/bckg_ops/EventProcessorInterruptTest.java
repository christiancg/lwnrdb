package org.techhouse.unit.bckg_ops;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EventProcessorInterruptTest {

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static EntityEvent indexedEvent() throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "f");
        IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("f"));
        final var data = new JsonObject();
        data.add(Globals.PK_FIELD, new JsonString("a1"));
        data.addProperty("f", 1);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id("a1");
        IocContainer.get(Cache.class).addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        return new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    @Test
    public void singleEventBatchRestoresTheInterruptAndReturnsTheEventDeferred() throws Exception {
        final var event = indexedEvent();
        Thread.currentThread().interrupt();

        final var deferred = EventProcessorHelper.processBatch(List.of(event));

        Assertions.assertTrue(Thread.interrupted());
        Assertions.assertEquals(List.of(event), deferred);
    }

    @Test
    public void multiEventBatchStillRestoresTheInterrupt() throws Exception {
        final var event = indexedEvent();
        Thread.currentThread().interrupt();

        EventProcessorHelper.processBatch(List.of(event, event));

        Assertions.assertTrue(Thread.interrupted());
    }
}

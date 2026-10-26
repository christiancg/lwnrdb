package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EntityEventIdsOnlyTest {
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static DbEntry entryOnPage(String id, long page) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("payload", new JsonString("some bytes"));
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.setPage(page);
        return entry;
    }

    private boolean hasPageRow(long number) {
        final var pages = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        return pages != null && pages.stream().anyMatch(p -> p.getPage() == number);
    }

    private AdminPageEntry page(long number) {
        final var pages = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(pages, "the collection has page rows");
        return pages.stream().filter(p -> p.getPage() == number).findFirst()
                .orElseThrow(() -> new AssertionError("no page row for page " + number));
    }

    private static boolean holdsADocument(Field field) {
        if (DbEntry.class.isAssignableFrom(field.getType())) {
            return true;
        }
        return field.getGenericType() instanceof ParameterizedType generic
                && Stream.of(generic.getActualTypeArguments()).anyMatch(argument -> argument == DbEntry.class);
    }

    @Test
    public void eventsHoldNoDocument() {
        for (final var type : List.of(EntityEvent.class, BulkEntityEvent.class)) {
            for (Class<?> level = type; level != null; level = level.getSuperclass()) {
                for (final var field : level.getDeclaredFields()) {
                    assertFalse(holdsADocument(field), level.getSimpleName() + "." + field.getName()
                            + " keeps a document alive in the queue; workers re-read it by id");
                }
            }
        }
    }

    @Test
    public void pageDeltaUsesRecordedPage() throws Exception {
        final var entry = entryOnPage("moved", 3);
        final var event = new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry);
        entry.setPage(7);

        EventProcessorHelper.processEvent(event);

        assertTrue(hasPageRow(3), "the delta belongs to the page the write landed on");
        assertFalse(hasPageRow(7), "no row appears for a page the event never named");
    }

    @Test
    public void failedRelocationDeltaLandsOnTheOriginalPage() throws Exception {
        final var original = entryOnPage("relocated", 0);
        EventProcessorHelper
                .processEvent(new BulkEntityEvent(TestGlobals.DB, TestGlobals.COLL, List.of(original), List.of()));
        final var before = page(0);
        assertNotNull(before);
        final var countBefore = before.getEntryCount();
        final var deleted = new EntityEvent(EventType.DELETED, TestGlobals.DB, TestGlobals.COLL, original);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0L, -original.byteSize(), -1);
        original.setPage(4);
        final var restored = new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, original);

        EventProcessorHelper.processEvent(deleted);
        EventProcessorHelper.processEvent(restored);

        assertEquals(countBefore - 1, page(0).getEntryCount(),
                "the delete's write-path delta left the page it was removed from, and its event did not apply it again");
        assertEquals(1, page(4).getEntryCount(), "the restore lands on the page it was re-inserted into");
    }
}

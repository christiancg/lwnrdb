package org.techhouse.unit.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PkRebuildAllocationBudgetTest {
    private static final int DOCS = 20_000;
    private static final int BULK = 1_000;
    private static final long PLAN_BYTES_PER_DOCUMENT = 1_270L;
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
            .getThreadMXBean();

    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        for (var start = 0; start < DOCS; start += BULK) {
            final var objects = new ArrayList<JsonObject>(BULK);
            for (var i = start; i < start + BULK; i++) {
                final var doc = new JsonObject();
                doc.add("_id", new JsonString("d" + i));
                doc.add("name", new JsonString("Customer number " + i));
                doc.add("qty", new JsonNumber(i % 97));
                objects.add(doc);
            }
            final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObjects(objects);
            locks.lock(TestGlobals.DB, TestGlobals.COLL);
            try {
                SaveOperationHelper.executeBulkSave(request);
            } finally {
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            }
        }
        TestUtils.discardQueuedBackgroundEvents(IocContainer.get(BackgroundTaskManager.class));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void rebuildHoldsNoIdHashMaps() throws Exception {
        var cheapest = Long.MAX_VALUE;
        for (var run = 0; run < 5; run++) {
            final var before = THREADS.getCurrentThreadAllocatedBytes();
            final var rebuild = fs.planPkRebuild(TestGlobals.DB, TestGlobals.COLL);
            cheapest = Math.min(cheapest, THREADS.getCurrentThreadAllocatedBytes() - before);
            assertEquals(DOCS, rebuild.entries());
            assertTrue(rebuild.appeared().isEmpty());
        }
        assertTrue(cheapest / DOCS <= PLAN_BYTES_PER_DOCUMENT,
                "a pk rebuild plan allocated " + cheapest / DOCS + " bytes per document");
    }
}

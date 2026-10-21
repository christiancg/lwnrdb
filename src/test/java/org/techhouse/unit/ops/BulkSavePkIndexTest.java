package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class BulkSavePkIndexTest {
    private static final int BATCHES = 12;
    private static final int BATCH_SIZE = 700;
    private static final int SCRAMBLE = 7919;
    private static final int ID_SPACE = 1_000_003;

    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

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

    private static BulkSaveRequest batch(int number) {
        final var objects = new ArrayList<JsonObject>(BATCH_SIZE);
        for (var i = 0; i < BATCH_SIZE; i++) {
            final var document = new JsonObject();
            document.addProperty("_id", "d" + (number * BATCH_SIZE + i) * SCRAMBLE % ID_SPACE);
            document.addProperty("n", number);
            objects.add(document);
        }
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(objects);
        return request;
    }

    @Test
    public void test_batches_past_the_point_edit_limit_keep_the_pk_index_sorted_and_complete() throws Exception {
        for (var number = 0; number < BATCHES; number++) {
            locks.lock(TestGlobals.DB, TestGlobals.COLL);
            try {
                SaveOperationHelper.executeBulkSave(batch(number));
            } finally {
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            }
        }

        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var index = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
            final var ids = new HashSet<String>();
            String previous = null;
            for (final var row : index) {
                assertTrue(previous == null || previous.compareTo(row.getValue()) < 0,
                        "pk index out of order at " + row.getValue());
                previous = row.getValue();
                ids.add(row.getValue());
            }
            assertEquals(BATCHES * BATCH_SIZE, index.size());
            assertEquals(BATCHES * BATCH_SIZE, ids.size());
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }
}

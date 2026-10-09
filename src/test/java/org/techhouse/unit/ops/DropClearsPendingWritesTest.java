package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.test.TestUtils;

public class DropClearsPendingWritesTest {
    private static final String DB = "pendingdropdb";
    private static final String COLL = "pendingdropcoll";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final PendingIndexWrites pending = IocContainer.get(PendingIndexWrites.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        createCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        pending.clearDatabase(DB);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void createCollection() {
        processor.processMessage(new CreateDatabaseRequest(DB));
        assertEquals(OperationStatus.OK, processor.processMessage(new CreateCollectionRequest(DB, COLL)).getStatus());
    }

    @Test
    public void test_a_dropped_collection_leaves_no_pending_ids_for_its_next_life() {
        pending.mark(DB, COLL, "old_life");

        assertEquals(OperationStatus.OK, processor.processMessage(new DropCollectionRequest(DB, COLL)).getStatus());
        assertTrue(pending.idsFor(DB, COLL).isEmpty());

        assertEquals(OperationStatus.OK, processor.processMessage(new CreateCollectionRequest(DB, COLL)).getStatus());
        pending.mark(DB, COLL, "new_life");
        assertTrue(fs.listDirtyIndexCollections().contains(DB + "|" + COLL),
                "the re-created collection's first pending write must write its own dirty marker");
    }

    @Test
    public void test_a_dropped_database_leaves_no_pending_ids_for_its_next_life() {
        pending.mark(DB, COLL, "old_life");

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(DB)).getStatus());
        assertTrue(pending.idsFor(DB, COLL).isEmpty());

        createCollection();
        pending.mark(DB, COLL, "new_life");
        assertEquals(List.of("new_life"), List.copyOf(pending.idsFor(DB, COLL)));
        assertTrue(fs.listDirtyIndexCollections().contains(DB + "|" + COLL));
    }
}

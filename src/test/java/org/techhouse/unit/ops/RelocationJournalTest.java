package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class RelocationJournalTest {
    private static final long MAX_PAGE_SIZE = 4096L;
    private static final int DOCUMENTS = 5;
    private static final int SMALL = 600;
    private static final int GROWN = 1600;
    private static final String RELOCATED = "r2";
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "maxPageSize", MAX_PAGE_SIZE);
        for (var i = 0; i < DOCUMENTS; i++) {
            assertEquals(OperationStatus.OK, save("r" + i, SMALL));
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", 2_097_152L);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationStatus save(String id, int payloadLength) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("payload", new JsonString("x".repeat(payloadLength)));
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private int payloadLengthOfTheRelocatedDocument() {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(RELOCATED);
        final var response = processor.processMessage(request);
        assertInstanceOf(FindByIdResponse.class, response, RELOCATED + " must stay reachable by id");
        return ((FindByIdResponse) response).getObject().get("payload").asJsonString().getValue().length();
    }

    private static File collectionFolder() {
        return new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);
    }

    private static String[] markers() {
        final var found = collectionFolder().list((_, name) -> name.endsWith(".compacting"));
        return found == null ? new String[0] : found;
    }

    @Test
    public void aSuccessfulRelocationLeavesNoMarker() {
        assertEquals(OperationStatus.OK, save(RELOCATED, GROWN));

        assertEquals(GROWN, payloadLengthOfTheRelocatedDocument());
        assertEquals(0, markers().length);
    }

    @Test
    public void aFailedInsertRestoresTheOldCopyAndEndsTheMarker() {
        final var blockedPage = new File(collectionFolder(),
                TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + "1" + Globals.DB_FILE_EXTENSION);
        assertTrue(blockedPage.mkdir());
        try {
            assertNotEquals(OperationStatus.OK, save(RELOCATED, GROWN));

            assertEquals(SMALL, payloadLengthOfTheRelocatedDocument());
            assertEquals(0, markers().length);
        } finally {
            assertTrue(blockedPage.delete());
        }
    }
}

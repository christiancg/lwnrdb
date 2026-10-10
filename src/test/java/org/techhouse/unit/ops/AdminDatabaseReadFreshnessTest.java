package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.UserCache;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.test.TestUtils;

public class AdminDatabaseReadFreshnessTest {
    private static final String FIRST = "freshness_first";
    private static final String SECOND = "freshness_second";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        assertEquals(OperationStatus.OK, processor.processMessage(new CreateDatabaseRequest(FIRST)).getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationStatus findDatabaseRecord(String dbName) {
        final var request = new FindByIdRequest(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
        request.set_id(dbName);
        return processor.processMessage(request).getStatus();
    }

    private List<String> scannedDatabaseRecords() {
        final var request = new AggregateRequest(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
        request.setAggregationSteps(List.of());
        final var response = processor.processMessage(request);
        assertInstanceOf(AggregateResponse.class, response);
        return ((AggregateResponse) response).getResults().stream()
                .map(record -> record.get(Globals.PK_FIELD).asJsonString().getValue()).sorted().toList();
    }

    @Test
    public void test_a_database_created_after_a_read_is_found() {
        assertEquals(OperationStatus.OK, findDatabaseRecord(FIRST));
        assertEquals(List.of(FIRST),
                scannedDatabaseRecords().stream().filter(name -> name.startsWith("freshness")).toList());

        assertEquals(OperationStatus.OK, processor.processMessage(new CreateDatabaseRequest(SECOND)).getStatus());

        assertEquals(OperationStatus.OK, findDatabaseRecord(SECOND));
        assertEquals(List.of(FIRST, SECOND),
                scannedDatabaseRecords().stream().filter(name -> name.startsWith("freshness")).toList());
    }

    @Test
    public void test_a_dropped_database_is_not_served_from_an_earlier_read() {
        assertEquals(OperationStatus.OK, processor.processMessage(new CreateDatabaseRequest(SECOND)).getStatus());
        assertEquals(OperationStatus.OK, findDatabaseRecord(FIRST));
        assertEquals(OperationStatus.OK, findDatabaseRecord(SECOND));

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(FIRST)).getStatus());

        assertNotEquals(OperationStatus.OK, findDatabaseRecord(FIRST));
        assertEquals(OperationStatus.OK, findDatabaseRecord(SECOND));
        assertEquals(List.of(SECOND),
                scannedDatabaseRecords().stream().filter(name -> name.startsWith("freshness")).toList());
    }

    @Test
    public void test_an_admin_read_publishes_nothing_to_the_user_cache() {
        assertEquals(OperationStatus.OK, findDatabaseRecord(FIRST));
        scannedDatabaseRecords();

        assertNull(userCache.getCachedCollection(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME));
        assertTrue(userCache.listCacheableResources().stream()
                .noneMatch(resource -> Globals.ADMIN_DB_NAME.equals(resource.dbName())));
    }
}

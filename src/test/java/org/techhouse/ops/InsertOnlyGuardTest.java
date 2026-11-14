package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class InsertOnlyGuardTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration configuration = Configuration.getInstance();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        assertEquals(OperationStatus.OK, processor.processMessage(request("stored", false)).getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemory", ConfigKey.MAX_MEMORY.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static SaveRequest request(String id, boolean failOnUpdate) {
        final var object = new JsonObject();
        object.addProperty("v", "x");
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        request.setFailOnUpdate(failOnUpdate);
        return request;
    }

    private String refusalCode(SaveRequest request) throws Exception {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var response = InsertOnlyGuard.check(request);
            return response == null ? null : response.getErrorCode();
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    @Test
    public void test_an_existing_id_is_refused_when_the_flag_is_set() throws Exception {
        assertEquals(ErrorCode.DOCUMENT_ALREADY_EXISTS.getCode(), refusalCode(request("stored", true)));
    }

    @Test
    public void test_an_absent_id_passes() throws Exception {
        assertNull(refusalCode(request("absent", true)));
    }

    @Test
    public void test_an_existing_id_passes_without_the_flag() throws Exception {
        assertNull(refusalCode(request("stored", false)));
    }

    @Test
    public void test_a_request_without_an_id_passes() throws Exception {
        assertNull(refusalCode(request(null, true)));
    }

    @Test
    public void test_a_collection_that_cannot_be_cached_is_answered_from_the_primary_key_file() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemory", Globals.CACHE_DISABLED);
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(ErrorCode.DOCUMENT_ALREADY_EXISTS.getCode(), refusalCode(request("stored", true)));
        assertNull(refusalCode(request("absent", true)));
    }
}

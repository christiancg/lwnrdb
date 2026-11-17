package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.index.IndexBuild;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CreateIndexMultiFieldTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem realFs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        for (var i = 0; i < 20; i++) {
            final var object = new JsonObject();
            object.addProperty("_id", "d" + i);
            object.addProperty("status", i % 2 == 0 ? "active" : "archived");
            object.addProperty("tier", "t" + i % 3);
            object.addProperty("score", i);
            final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObject(object);
            assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        }
        cache.userCache().evictCollection(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(IndexBuild.class, "fs", realFs);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private FileSystem spyTheBuild() throws Exception {
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(IndexBuild.class, "fs", spied);
        return spied;
    }

    private OperationResponse create(List<String> fields) {
        return processor.processMessage(CreateIndexRequest.ofFields(TestGlobals.DB, TestGlobals.COLL, fields));
    }

    private Set<String> registered() {
        return cache.getIndexesForCollection(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_field_names_build_every_missing_field_in_one_pass() throws Exception {
        final var spied = spyTheBuild();

        final var response = create(List.of("status", "tier", "score"));

        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertEquals("Created index for fields: status, tier, score", response.getMessage());
        assertEquals(Set.of("status", "tier", "score"), registered());
        verify(spied, times(1)).streamPages(TestGlobals.DB, TestGlobals.COLL);
        for (final var field : List.of("status", "tier", "score")) {
            assertFalse(realFs.indexMarkers().isMarked(TestGlobals.DB, TestGlobals.COLL, field), field);
        }
    }

    @Test
    public void test_an_already_registered_field_is_skipped() throws Exception {
        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "status")).getStatus());
        final var spied = spyTheBuild();

        final var response = create(List.of("status", "tier"));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Created index for field: tier; already existed for field: status", response.getMessage());
        verify(spied, times(1)).writeIndexFile(eq(TestGlobals.DB), eq(TestGlobals.COLL), eq("tier"), any());
        verify(spied, never()).writeIndexFile(anyString(), anyString(), eq("status"), any());
        assertEquals(Set.of("status", "tier"), registered());
    }

    @Test
    public void test_every_field_already_registered_builds_nothing() throws Exception {
        create(List.of("status", "tier"));
        final var spied = spyTheBuild();

        final var response = create(List.of("tier", "status"));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Index already exists for fields: tier, status", response.getMessage());
        verify(spied, never()).streamPages(anyString(), anyString());
    }

    @Test
    public void test_a_colliding_pair_refuses_the_whole_request_before_any_build() throws Exception {
        final var spied = spyTheBuild();

        final var response = create(List.of("score", "tier", "TIER"));

        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(), response.getErrorCode());
        assertTrue(registered().isEmpty());
        verify(spied, never()).streamPages(anyString(), anyString());
    }

    @Test
    public void test_field_name_and_field_names_are_merged_without_duplicates() {
        final var request = CreateIndexRequest.ofFields(TestGlobals.DB, TestGlobals.COLL,
                List.of("tier", "status", "tier"));
        request.setFieldName("score");

        assertEquals(List.of("score", "tier", "status"), request.targetFieldNames());
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        assertEquals(Set.of("score", "tier", "status"), registered());
    }

    @Test
    public void aHugeFieldNamesListIsRefusedBeforeTheCollisionScan() throws Exception {
        final var spied = spyTheBuild();
        final var fields = new ArrayList<String>();
        for (var i = 0; i < 30_000; i++) {
            fields.add("f" + i);
        }

        final var response = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> create(fields));

        assertEquals(ErrorCode.TOO_MANY_INDEXES_FOR_COLLECTION.getCode(), response.getErrorCode());
        assertTrue(registered().isEmpty());
        verify(spied, never()).streamPages(anyString(), anyString());
    }

    @Test
    public void aCollisionAmongManyMissingFieldsNamesTheFirstSpelling() {
        final var response = create(List.of("alpha", "beta", "Alpha"));

        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("alpha"), response.getMessage());
    }
}

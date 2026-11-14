package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestUtils;

public class GroupCommitExistenceTest {
    private static final String EXISTS = ErrorCode.DOCUMENT_ALREADY_EXISTS.getCode();
    private RecordingForcer recorder;
    private GroupCommitFixture fixture;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        recorder = RecordingForcer.install(true);
        fixture = new GroupCommitFixture(recorder);
        assertEquals(OperationStatus.OK, fixture.save("seed", "v0").getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        recorder.close();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse insert(String id, String value) {
        final var request = GroupCommitFixture.saveRequest(id, value);
        request.setFailOnUpdate(true);
        return fixture.process(request);
    }

    @Test
    public void test_two_inserts_of_one_new_id_in_one_queue_admit_exactly_one() throws Exception {
        final var responses = fixture.runQueued(List.of(() -> insert("twin", "first"), () -> insert("twin", "second")));

        assertEquals(OperationStatus.OK, responses.get(0).getStatus());
        assertEquals(EXISTS, responses.get(1).getErrorCode());
        assertEquals("first", fixture.valueOf("twin"));
        assertEquals(1L, fixture.walForces(), "the refused insert forces nothing of its own");
    }

    @Test
    public void test_an_insert_queued_after_a_delete_of_its_id_sees_the_delete() throws Exception {
        final var responses = fixture.runQueued(List.of(() -> fixture.delete("seed"), () -> insert("seed", "again")));

        assertEquals(OperationStatus.OK, responses.get(0).getStatus());
        assertEquals(OperationStatus.OK, responses.get(1).getStatus());
        assertEquals("again", fixture.valueOf("seed"));
    }

    @Test
    public void test_an_insert_queued_after_a_plain_save_of_its_id_is_refused() throws Exception {
        final var responses = fixture
                .runQueued(List.of(() -> fixture.save("fresh", "saved"), () -> insert("fresh", "inserted")));

        assertEquals(OperationStatus.OK, responses.get(0).getStatus());
        assertEquals(EXISTS, responses.get(1).getErrorCode());
        assertEquals("saved", fixture.valueOf("fresh"));
    }

    @Test
    public void test_an_insert_after_a_taken_back_write_of_its_id_succeeds() throws Exception {
        final var failedOnce = new AtomicBoolean();
        recorder.refusingWhen(
                (_, name) -> GroupCommitFixture.WAL.equals(name) && failedOnce.compareAndSet(false, true));

        final var responses = fixture
                .runQueued(List.of(() -> fixture.save("lost", "taken-back"), () -> insert("lost", "inserted")));

        assertEquals(ErrorCode.ERROR_SAVING.getCode(), responses.get(0).getErrorCode());
        assertEquals(OperationStatus.OK, responses.get(1).getStatus());
        assertEquals("inserted", fixture.valueOf("lost"));
    }

    @Test
    public void test_inserts_of_distinct_ids_still_share_one_force() throws Exception {
        final var responses = fixture.runQueued(GroupCommitFixture.times(6, number -> insert("i" + number, "v")));

        assertTrue(responses.stream().allMatch(response -> response.getStatus() == OperationStatus.OK));
        assertEquals(1L, fixture.walForces());
    }

    @Test
    public void test_an_insert_of_an_existing_id_alone_is_refused_and_leaves_it() {
        assertEquals(EXISTS, insert("seed", "changed").getErrorCode());
        assertEquals("v0", fixture.valueOf("seed"));
    }
}

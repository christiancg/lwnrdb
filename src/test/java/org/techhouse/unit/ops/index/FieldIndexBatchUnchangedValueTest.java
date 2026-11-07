package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class FieldIndexBatchUnchangedValueTest {
    private static final String FIELD = "tag";
    private static final String SLOT_CLASS = "org.techhouse.ops.index.FieldIndexSlot";
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private FieldIndexBatchSupport support;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        support = new FieldIndexBatchSupport();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", realFs);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private FileSystem spyTheBatchFileSystem() throws Exception {
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", spied);
        return spied;
    }

    private void seedScalars() throws Exception {
        support.put("n1", new JsonNumber(5));
        support.put("s1", new JsonString("Abc"));
        support.put("b1", JsonBoolean.of(true));
        support.applyBatch(FIELD, List.of("n1", "s1", "b1"));
    }

    private void rewriteTheSameScalars() {
        support.put("n1", new JsonNumber("5.0"));
        support.put("s1", new JsonString("Abc"));
        support.put("b1", JsonBoolean.of(true));
    }

    @Test
    public void test_unchanged_scalars_append_nothing_when_resident() throws Exception {
        seedScalars();
        support.admitEveryType(FIELD);
        rewriteTheSameScalars();
        final var spied = spyTheBatchFileSystem();

        support.applyBatch(FIELD, List.of("n1", "s1", "b1"));

        verify(spied, never()).appendIndexLog(anyString(), anyString(), anyString(), anyString(), any(), any(),
                anyBoolean());
        assertNull(support.residentMismatch(FIELD));
    }

    @Test
    public void test_unchanged_scalars_append_nothing_when_read_from_disk() throws Exception {
        seedScalars();
        rewriteTheSameScalars();
        final var spied = spyTheBatchFileSystem();

        support.applyBatch(FIELD, List.of("n1", "s1", "b1"));

        verify(spied, never()).appendIndexLog(anyString(), anyString(), anyString(), anyString(), any(), any(),
                anyBoolean());
    }

    @Test
    public void test_zero_and_negative_zero_are_the_same_entry() throws Exception {
        support.put("z1", new JsonNumber(0.0));
        support.applyBatch(FIELD, List.of("z1"));
        support.put("z1", new JsonNumber(-0.0));
        final var spied = spyTheBatchFileSystem();

        support.applyBatch(FIELD, List.of("z1"));

        verify(spied, never()).appendIndexLog(anyString(), anyString(), anyString(), anyString(), any(), any(),
                anyBoolean());
    }

    @Test
    public void test_a_value_changed_in_case_only_still_moves() throws Exception {
        seedScalars();
        support.put("s1", new JsonString("abc"));

        support.applyBatch(FIELD, List.of("s1"));

        assertEquals(Set.of("abc => s1"), FieldIndexBatchSupport.snapshot(FIELD).get("String"));
    }

    @Test
    public void test_a_value_that_changes_type_moves_to_the_other_type() throws Exception {
        seedScalars();
        support.put("n1", new JsonString("5"));

        support.applyBatch(FIELD, List.of("n1"));

        final var snapshot = FieldIndexBatchSupport.snapshot(FIELD);
        assertEquals(Set.of("5 => n1", "Abc => s1"), snapshot.get("String"));
        assertNull(snapshot.get("Number"));
    }

    @Test
    public void test_a_removed_value_removes_the_id() throws Exception {
        seedScalars();
        support.delete("b1");

        support.applyBatch(FIELD, List.of("b1"));

        assertEquals(Map.of("Number", Set.of("5 => n1"), "String", Set.of("Abc => s1")),
                FieldIndexBatchSupport.snapshot(FIELD));
    }

    @Test
    public void test_batches_that_mostly_keep_their_values_stay_equal_to_a_fresh_load() throws Exception {
        final var random = new Random(91);
        final var ids = FieldIndexBatchSupport.randomIds(random, 60, 60);
        for (final var id : ids) {
            support.mutate(id, random);
        }
        support.applyBatch(FIELD, ids);
        for (var batch = 0; batch < 120; batch++) {
            if (batch % 20 == 0) {
                support.admitEveryType(FIELD);
            }
            final var touched = FieldIndexBatchSupport.randomIds(random, 1 + random.nextInt(15), 60);
            for (final var id : touched) {
                if (random.nextInt(10) < 3) {
                    support.mutate(id, random);
                }
            }
            support.applyBatch(FIELD, touched);
            assertNull(support.residentMismatch(FIELD), "after batch " + batch);
            assertNull(support.lookupMismatch(FIELD, 60), "after batch " + batch);
        }
    }
}

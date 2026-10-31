package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class FieldIndexBatchRefusedTypeTest {
    private static final String FIELD = "f";
    private static final String SLOT_CLASS = "org.techhouse.ops.index.FieldIndexSlot";
    private static final int POOL = 12;
    private static final long REFUSE_EVERYTHING = 1L;

    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private Long savedMaxMemory;
    private RefusedTypeWorld world;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        maxMemory(Globals.CACHE_DISABLED);
        world = new RefusedTypeWorld(FIELD, POOL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", realFs);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void maxMemory(long bytes) throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", bytes);
    }

    private void seedEveryId(ValueFactory value) throws Exception {
        final var ids = new ArrayList<String>();
        for (var i = 0; i < POOL; i++) {
            world.support().put("id" + i, value.of(i));
            ids.add("id" + i);
        }
        world.seed(ids);
    }

    @FunctionalInterface
    private interface ValueFactory {
        JsonBaseElement of(int i);
    }

    private void assertConsistent() throws Exception {
        assertNull(world.mismatch());
    }

    private static JsonString text(String value) {
        return new JsonString(value);
    }

    @Test
    public void twoWritesEachProcessedBeforeTheNext() throws Exception {
        seedEveryId(i -> text("a" + i));
        final var first = world.write("id1", text("b"));
        world.process(List.of(first), true);
        final var second = world.write("id1", text("c"));
        world.process(List.of(second), true);

        assertConsistent();
    }

    @Test
    public void twoWritesProcessedInOrderAfterBoth() throws Exception {
        seedEveryId(i -> text("a" + i));
        final var first = world.write("id1", text("b"));
        final var second = world.write("id1", text("c"));
        world.process(List.of(first), true);
        world.process(List.of(second), true);

        assertConsistent();
    }

    @Test
    public void twoWritesProcessedInReverse() throws Exception {
        seedEveryId(i -> text("a" + i));
        final var first = world.write("id1", text("b"));
        final var second = world.write("id1", text("c"));
        world.process(List.of(second), true);
        world.process(List.of(first), true);

        assertConsistent();
    }

    @Test
    public void threeWritesProcessedInReverse() throws Exception {
        seedEveryId(i -> text("a" + i));
        final var first = world.write("id1", text("b"));
        final var second = world.write("id1", text("c"));
        final var third = world.write("id1", text("a1"));
        world.process(List.of(third), true);
        world.process(List.of(second), true);
        world.process(List.of(first), true);

        assertConsistent();
    }

    @Test
    public void threeWritesInOneBatch() throws Exception {
        seedEveryId(i -> text("a" + i));
        final var first = world.write("id1", text("b"));
        final var second = world.write("id1", text("c"));
        final var third = world.write("id1", text("d"));
        world.process(List.of(third, first, second), true);

        assertConsistent();
    }

    @Test
    public void aDeleteThenARecreateWithTheSameValueInEitherOrder() throws Exception {
        seedEveryId(i -> text("a" + i));
        final var deleted = world.delete("id2");
        final var recreated = world.write("id2", text("a2"));
        world.process(List.of(recreated), true);
        world.process(List.of(deleted), true);
        assertConsistent();

        final var deletedAgain = world.delete("id3");
        world.process(List.of(deletedAgain), true);
        final var recreatedAgain = world.write("id3", text("a3"));
        world.process(List.of(recreatedAgain), true);
        assertConsistent();
    }

    @Test
    public void anIdMovingThroughEveryTypeProcessedInReverse() throws Exception {
        seedEveryId(i -> text("a" + i));
        final var object = new JsonObject();
        object.add("k", new JsonNumber(1));
        final var array = new JsonArray();
        array.add(text("x"));
        final var events = new ArrayList<RefusedTypeWorld.Event>();
        events.add(world.write("id4", new JsonNumber(7)));
        events.add(world.write("id4", object));
        events.add(world.write("id4", array));
        events.add(world.write("id4", JsonBoolean.of(true)));
        events.add(world.write("id4", text("#geo(1,2)")));
        events.add(world.write("id4", JsonNull.INSTANCE));
        events.add(world.write("id4", new JsonNumber(8)));
        for (final var event : events.reversed()) {
            world.process(List.of(event), true);
        }

        assertConsistent();
    }

    @Test
    public void anEventWithoutPriorsTakesTheLoadPathBesideLoggedOnes() throws Exception {
        seedEveryId(i -> text("a" + i));
        final var first = world.write("id5", text("b"));
        final var second = world.write("id5", new JsonNumber(3));
        final var third = world.write("id5", text("c"));
        world.process(List.of(third), true);
        world.process(List.of(first), false);
        world.process(List.of(second), true);

        assertConsistent();
    }

    @Test
    public void aLoggedBatchNeverReadsTheRefusedType() throws Exception {
        seedEveryId(i -> text("a" + i));
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", spied);

        world.process(List.of(world.write("id6", text("b"))), true);

        verify(spied, never()).readWholeFieldIndexFiles(anyString(), anyString(), eq(FIELD), eq(String.class));
        verify(spied, never()).readWholeHashIndexFile(anyString(), anyString(), eq(FIELD), any(IndexKind.class));
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", realFs);
        assertConsistent();
    }

    @Test
    public void aBatchWithoutPriorsStillReadsTheType() throws Exception {
        seedEveryId(i -> text("a" + i));
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", spied);

        world.process(List.of(world.write("id6", text("b"))), false);

        verify(spied).readWholeFieldIndexFiles(anyString(), anyString(), eq(FIELD), eq(String.class));
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", realFs);
        assertConsistent();
    }

    @Test
    public void aLogPastItsFoldThresholdIsFoldedIntoTheBase() throws Exception {
        seedEveryId(i -> text("a" + i));
        final var padding = "p".repeat(2000);
        for (var round = 0; round < 40; round++) {
            final var events = new ArrayList<RefusedTypeWorld.Event>();
            for (var i = 0; i < POOL; i++) {
                events.add(world.write("id" + i, text(padding + round + "-" + i)));
            }
            world.process(events, true);
            assertConsistent();
        }

        assertTrue(FieldIndexBatchSupport.logFile(FIELD, IndexKind.STRING.label()).length() < 64 * 1024);
        assertTrue(FieldIndexBatchSupport.indexFile(FIELD, IndexKind.STRING.label()).exists());
    }

    @Test
    public void aRefusedTypeEmptiedThroughItsLogIsDeletedByTheFold() throws Exception {
        seedEveryId(i -> text("p".repeat(3000) + i));
        final var events = new ArrayList<RefusedTypeWorld.Event>();
        for (var round = 0; round < 3; round++) {
            for (var i = 0; i < POOL; i++) {
                events.add(world.write("id" + i, text("q".repeat(3000) + round + i)));
            }
        }
        for (var i = 0; i < POOL; i++) {
            events.add(world.write("id" + i, new JsonNumber(i)));
        }
        world.process(events, true);

        assertConsistent();
        assertFalse(FieldIndexBatchSupport.typeExists(FIELD, IndexKind.STRING.label()));
    }

    @Test
    public void aRefusedTypeBesideAResidentOneWhileIdsMoveBetweenThem() throws Exception {
        maxMemory(Globals.CACHE_UNLIMITED);
        seedEveryId(i -> i % 2 == 0 ? text("a" + i) : new JsonNumber(i));
        world.support().admitEveryType(FIELD);
        maxMemory(REFUSE_EVERYTHING);
        IocContainer.get(Cache.class).evictFieldIndex(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL,
                Cache.getIndexIdentifier(FIELD, String.class));
        world.process(List.of(world.write("id0", text("refused-load"))), true);
        final var toNumber = world.write("id2", new JsonNumber(100));
        final var toString = world.write("id1", text("from-number"));
        world.process(List.of(toString, toNumber), true);

        assertConsistent();
        assertNull(world.support().residentMismatch(FIELD));
        assertNull(world.support().resident(FIELD, String.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 7L, 42L, 20261030L, 99L, 123456L})
    public void randomInterleavingsMatchTheDocuments(long seed) throws Exception {
        runRandom(seed, false);
    }

    @ParameterizedTest
    @ValueSource(longs = {3L, 11L, 20261031L, 77L})
    public void randomInterleavingsWithResidencyChangesMatchTheDocuments(long seed) throws Exception {
        runRandom(seed, true);
    }

    private void runRandom(long seed, boolean flipResidency) throws Exception {
        final var random = new Random(seed);
        if (flipResidency) {
            maxMemory(Globals.CACHE_UNLIMITED);
        }
        seedEveryId(_ -> FieldIndexBatchSupport.randomValue(random));
        for (var step = 0; step < 400; step++) {
            final var roll = random.nextInt(10);
            if (roll < 5) {
                final var id = "id" + random.nextInt(POOL);
                if (random.nextInt(8) == 0) {
                    world.delete(id);
                } else {
                    world.write(id, FieldIndexBatchSupport.randomValue(random));
                }
            } else if (roll < 9 && !world.unprocessed().isEmpty()) {
                world.process(randomBatch(random), random.nextInt(6) != 0);
                assertNull(world.mismatch(), "seed " + seed + ", step " + step);
            } else if (flipResidency) {
                flip(random);
            }
        }
        while (!world.unprocessed().isEmpty()) {
            world.process(randomBatch(random), true);
        }
        assertNull(world.mismatch(), "seed " + seed);
        assertEquals(List.of(), world.unprocessed());
    }

    private List<RefusedTypeWorld.Event> randomBatch(Random random) {
        final var pool = new ArrayList<>(world.unprocessed());
        final var batch = new ArrayList<RefusedTypeWorld.Event>();
        final var size = 1 + random.nextInt(Math.min(4, pool.size()));
        for (var i = 0; i < size; i++) {
            batch.add(pool.remove(random.nextInt(pool.size())));
        }
        return batch;
    }

    private void flip(Random random) throws Exception {
        if (random.nextBoolean()) {
            maxMemory(REFUSE_EVERYTHING);
            IocContainer.get(Cache.class).evictFieldIndexAllTypes(FieldIndexBatchSupport.DB,
                    FieldIndexBatchSupport.COLL, FIELD);
        } else {
            maxMemory(Globals.CACHE_UNLIMITED);
            world.support().admitEveryType(FIELD);
        }
    }
}

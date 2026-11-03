package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.IndexIdCounts;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.IndexFileLookup;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class IndexIdCountsTest {
    private static final String FIELD = "counted";
    private static final int POOL = 160;
    private static final List<Class<?>> TYPES = List.of(Number.class, String.class, Boolean.class);

    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final IndexIdCounts idCounts = IocContainer.get(IndexIdCounts.class);
    private Long savedMaxMemory;
    private RefusedTypeWorld world;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        world = new RefusedTypeWorld(FIELD, POOL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        idCounts.forgetDatabase(FieldIndexBatchSupport.DB);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void seed(Random random) throws Exception {
        final var ids = new ArrayList<String>();
        for (var i = 0; i < POOL; i++) {
            world.support().put("id" + i, valueOf(random));
            ids.add("id" + i);
        }
        world.seed(ids);
    }

    private static org.techhouse.ejson.elements.JsonBaseElement valueOf(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> new JsonNumber(random.nextInt(20));
            case 1 -> new JsonString(random.nextBoolean() ? "v" + random.nextInt(9) : "V" + random.nextInt(9));
            default -> org.techhouse.ejson.elements.JsonBoolean.of(random.nextBoolean());
        };
    }

    private long fullLoadCount(Class<?> type) throws Exception {
        final var loaded = fs.readWholeFieldIndexFiles(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, FIELD,
                type);
        if (loaded == null) {
            return IndexIdCounts.UNKNOWN;
        }
        var count = 0L;
        for (final FieldIndexEntry<?> entry : loaded) {
            count += entry.getIds().size();
        }
        return count;
    }

    private long counted(Class<?> type) throws Exception {
        return idCounts.count(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, identifier(type),
                () -> fs.indexFiles().idCount(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, FIELD, type));
    }

    private static String identifier(Class<?> type) {
        return Cache.getIndexIdentifier(FIELD, type);
    }

    private long known(Class<?> type) {
        return idCounts.known(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, identifier(type));
    }

    private void mutate(Random random, boolean withPriors) throws Exception {
        final var events = new ArrayList<RefusedTypeWorld.Event>();
        for (var i = 0; i < 12; i++) {
            final var id = "id" + random.nextInt(POOL);
            events.add(random.nextInt(8) == 0 ? world.delete(id) : world.write(id, valueOf(random)));
        }
        world.process(events, withPriors);
    }

    @Test
    public void countMatchesFullLoad() throws Exception {
        final var random = new Random(7);
        seed(random);
        for (var round = 0; round < 6; round++) {
            mutate(random, false);
        }
        for (final var type : TYPES) {
            assertTrue(fs.indexTypeFileExists(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, FIELD,
                    IndexKind.fileLabel(type)));
            assertEquals(fullLoadCount(type), counted(type), type.getSimpleName());
            assertEquals(fullLoadCount(type), known(type));
        }
        assertEquals(IndexFileLookup.DECLINED, fs.indexFiles().idCount(FieldIndexBatchSupport.DB,
                FieldIndexBatchSupport.COLL, "missing", Number.class));
    }

    @Test
    public void adjustTracksBatches() throws Exception {
        final var random = new Random(11);
        seed(random);
        for (final var type : TYPES) {
            counted(type);
        }
        for (var round = 0; round < 20; round++) {
            mutate(random, false);
            for (final var type : TYPES) {
                assertNotEquals(IndexIdCounts.UNKNOWN, known(type), "a loaded batch keeps the count exact");
                assertEquals(fullLoadCount(type), known(type), type.getSimpleName() + " after round " + round);
            }
        }
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", Globals.CACHE_DISABLED);
        mutate(random, true);
        for (final var type : TYPES) {
            final var known = known(type);
            assertTrue(known == IndexIdCounts.UNKNOWN || known == fullLoadCount(type),
                    "a log-only batch cannot know its delta, so it forgets the count");
            assertEquals(fullLoadCount(type), counted(type));
        }
    }

    @Test
    public void forgottenOnEvict() throws Exception {
        seed(new Random(3));
        counted(Number.class);
        cache.evictFieldIndex(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, identifier(Number.class));
        assertEquals(IndexIdCounts.UNKNOWN, known(Number.class));
        counted(Number.class);
        counted(String.class);
        cache.evictFieldIndexAllTypes(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, FIELD);
        assertEquals(IndexIdCounts.UNKNOWN, known(Number.class));
        assertEquals(IndexIdCounts.UNKNOWN, known(String.class));
        counted(Number.class);
        cache.evictCollection(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL);
        assertEquals(IndexIdCounts.UNKNOWN, known(Number.class));
        counted(Number.class);
        cache.evictDatabase(FieldIndexBatchSupport.DB);
        assertEquals(IndexIdCounts.UNKNOWN, known(Number.class));
    }

    @Test
    public void aForgetDuringACountKeepsItUnknown() throws Exception {
        seed(new Random(5));
        final var count = idCounts.count(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL,
                identifier(Number.class), () -> {
                    idCounts.forget(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, identifier(Number.class));
                    return 42L;
                });
        assertEquals(42L, count);
        assertEquals(IndexIdCounts.UNKNOWN, known(Number.class));
        idCounts.count(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, identifier(Number.class), () -> 3L);
        idCounts.adjust(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, identifier(Number.class), 1, 5);
        assertEquals(IndexIdCounts.UNKNOWN, known(Number.class), "a count that would go negative is dropped");
    }
}

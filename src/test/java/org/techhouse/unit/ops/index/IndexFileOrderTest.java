package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FieldIndexLoader;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.index.IndexBuild;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.test.TestUtils;

public class IndexFileOrderTest {
    private static final String FIELD = "ordered";
    private static final int POOL = 2500;

    private final Configuration configuration = Configuration.getInstance();
    private Long savedMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonBaseElement valueOf(Random random) {
        return switch (random.nextInt(7)) {
            case 0, 1 -> new JsonNumber(random.nextInt(4000) - 2000 + (random.nextBoolean() ? 0.5 : 0));
            case 2, 3 -> new JsonString((random.nextBoolean() ? "K" : "k") + random.nextInt(900));
            case 4 -> JsonBoolean.of(random.nextBoolean());
            case 5 -> new JsonString("#datetime(2024-01-" + (10 + random.nextInt(18)) + "T10:00:00)");
            default -> random.nextBoolean() ? objectOf(random.nextInt(50)) : arrayOf(random.nextInt(50));
        };
    }

    private static JsonObject objectOf(int value) {
        final var object = new JsonObject();
        object.add("k", new JsonNumber(value));
        return object;
    }

    private static JsonArray arrayOf(int value) {
        final var array = new JsonArray();
        array.add(new JsonNumber(value));
        return array;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assertEveryTypeFileSorted(String label) throws Exception {
        var files = 0;
        for (final var type : IndexKind.scalarIndexClasses()) {
            final var file = FieldIndexBatchSupport.indexFile(FIELD, IndexKind.fileLabel(type));
            if (file.exists()) {
                files++;
                assertSorted(label + " " + type.getSimpleName(), file.toPath(), type,
                        (Comparator) FieldIndexLoader.orderFor(type));
            }
        }
        for (final var kind : IndexKind.HASH_KINDS) {
            final var file = FieldIndexBatchSupport.indexFile(FIELD, kind.label());
            if (file.exists()) {
                files++;
                assertSorted(label + " " + kind.label(), file.toPath(), String.class,
                        (Comparator) FieldIndexLoader.hashOrder());
            }
        }
        assertTrue(files > 0, label);
    }

    private static void assertSorted(String label, java.nio.file.Path file, Class<?> type,
            Comparator<FieldIndexEntry<Object>> order) throws Exception {
        FieldIndexEntry<Object> previous = null;
        for (final var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final var entry = (FieldIndexEntry<Object>) FieldIndexEntry.fromIndexFileEntry(FieldIndexBatchSupport.DB,
                    FieldIndexBatchSupport.COLL, line, type);
            if (previous != null) {
                assertTrue(order.compare(previous, entry) < 0,
                        label + ": " + previous.getValue() + " is written before " + entry.getValue());
            }
            previous = entry;
        }
    }

    @Test
    public void aBuildWritesEveryTypeInOrderForOrder() throws Exception {
        final var random = new Random(17);
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < POOL; i++) {
            final var doc = new JsonObject();
            doc.add(Globals.PK_FIELD, new JsonString("b" + i));
            doc.add(FIELD, valueOf(random));
            objects.add(doc);
        }
        final var bulk = new BulkSaveRequest(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL);
        bulk.setObjects(objects);
        assertEquals(OperationStatus.OK, IocContainer.get(OperationProcessor.class).processMessage(bulk).getStatus());
        IndexBuild.buildAll(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, List.of(FIELD));
        assertEveryTypeFileSorted("build");
    }

    private static List<String> labelsWithALog() {
        final var labels = new ArrayList<String>();
        for (final var label : FieldIndexBatchSupport.typeLabels()) {
            if (FieldIndexBatchSupport.logFile(FIELD, label).exists()) {
                labels.add(label);
            }
        }
        return labels;
    }

    private void foldAtLeastOnce(RefusedTypeWorld world, Random random, boolean withPriors) throws Exception {
        var folds = 0;
        for (var round = 0; round < 60 && folds < 3; round++) {
            final var logged = labelsWithALog();
            final var events = new ArrayList<RefusedTypeWorld.Event>();
            for (var i = 0; i < 400; i++) {
                events.add(world.write("id" + random.nextInt(POOL), valueOf(random)));
            }
            world.process(events, withPriors);
            for (final var label : logged) {
                if (!FieldIndexBatchSupport.logFile(FIELD, label).exists()) {
                    folds++;
                }
            }
        }
        assertTrue(folds > 0, "sixty rounds of 400 changes must fold a type at least once");
    }

    @Test
    public void aFoldRewritesEveryTypeInOrderForOrder() throws Exception {
        final var random = new Random(23);
        final var world = new RefusedTypeWorld(FIELD, POOL);
        final var ids = new ArrayList<String>();
        for (var i = 0; i < POOL; i++) {
            world.support().put("id" + i, valueOf(random));
            ids.add("id" + i);
        }
        world.seed(ids);
        foldAtLeastOnce(world, random, false);
        assertEveryTypeFileSorted("loaded fold");
        assertNull(world.mismatch());

        TestUtils.setPrivateField(configuration, "maxMemoryBytes", Globals.CACHE_DISABLED);
        foldAtLeastOnce(world, random, true);
        assertEveryTypeFileSorted("log-only fold");
        assertNull(world.mismatch());
    }
}

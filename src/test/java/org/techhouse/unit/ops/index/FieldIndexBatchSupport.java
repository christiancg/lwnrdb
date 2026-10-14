package org.techhouse.unit.ops.index;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FieldIndexLoader;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.FieldIndexBatch;
import org.techhouse.test.TestGlobals;
import org.techhouse.utils.JsonUtils;

final class FieldIndexBatchSupport {
    static final String DB = TestGlobals.DB;
    static final String COLL = TestGlobals.COLL;
    private static final Number[] NUMBERS = {0, -0.0, 0.0, 1, 1.0, 2, 2.5, -3, 1e20, 123456789012L, -0.5};
    private static final String[] STRINGS = {"a", "A", "b", "B", "ab", "AB", "Ab", "é", "É", "x\u001fy", "back\\slash",
            "line\nfeed", "cr\rhere", "\ud800", "😀", "", " "};
    private static final String[] CUSTOMS = {"#geo(1,2)", "#geo(1.0,2.0)", "#datetime(2024-01-01T10:00)",
            "#datetime(2024-01-01T10:00:00)", "#datetime(2023-10-01T10:00:00)"};

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Map<String, JsonBaseElement> values = new TreeMap<>();
    private final Set<String> deleted = new HashSet<>();

    static JsonBaseElement randomValue(Random random) {
        return switch (random.nextInt(8)) {
            case 0, 1 -> new JsonNumber(NUMBERS[random.nextInt(NUMBERS.length)]);
            case 2, 3 -> new JsonString(STRINGS[random.nextInt(STRINGS.length)]);
            case 4 -> JsonBoolean.of(random.nextBoolean());
            case 5 -> new JsonString(CUSTOMS[random.nextInt(CUSTOMS.length)]);
            case 6 -> randomContainer(random);
            default -> JsonNull.INSTANCE;
        };
    }

    private static JsonBaseElement randomContainer(Random random) {
        final var which = random.nextInt(4);
        if (which < 2) {
            final var object = new JsonObject();
            if (which == 1) {
                object.add("k", new JsonNumber(random.nextInt(3)));
            }
            return object;
        }
        final var array = new JsonArray();
        final var size = random.nextInt(3);
        for (var i = 0; i < size; i++) {
            array.add(new JsonString(STRINGS[random.nextInt(3)]));
        }
        return array;
    }

    void mutate(String id, Random random) {
        final var roll = random.nextInt(10);
        if (roll == 0) {
            delete(id);
        } else if (roll == 1) {
            deleted.remove(id);
            values.remove(id);
        } else {
            put(id, randomValue(random));
        }
    }

    void put(String id, JsonBaseElement value) {
        deleted.remove(id);
        values.put(id, value);
    }

    void delete(String id) {
        deleted.add(id);
        values.remove(id);
    }

    DbEntry documentOf(String id, String... fields) {
        if (deleted.contains(id)) {
            return null;
        }
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        final var value = values.get(id);
        if (value != null) {
            for (final var field : fields) {
                object.add(field, value);
            }
        }
        return DbEntry.fromJsonObject(DB, COLL, object);
    }

    void applyBatch(String fieldName, List<String> ids) throws IOException, InterruptedException {
        locks.lockIndex(DB, COLL, fieldName);
        try {
            final var batch = FieldIndexBatch.open(DB, COLL, fieldName);
            for (final var id : ids) {
                final var doc = documentOf(id, fieldName);
                batch.apply(id, doc == null ? null : JsonUtils.getFromPath(doc.getData(), fieldName));
            }
            batch.flush();
        } finally {
            locks.releaseIndex(DB, COLL, fieldName);
        }
    }

    void applyBatchHoldingTheCollection(String fieldName, List<String> ids) throws IOException, InterruptedException {
        locks.lockRead(DB, COLL);
        try {
            applyBatch(fieldName, ids);
        } finally {
            locks.releaseRead(DB, COLL);
        }
    }

    static List<String> randomIds(Random random, int count, int pool) {
        final var ids = new ArrayList<String>(count);
        for (var i = 0; i < count; i++) {
            ids.add("id" + random.nextInt(pool));
        }
        return ids;
    }

    static File indexFile(String fieldName, String typeLabel) {
        return new File(TestGlobals.PATH + File.separator + DB + File.separator + COLL + File.separator + COLL + "-"
                + fieldName + "-" + typeLabel + Globals.INDEX_FILE_EXTENSION);
    }

    static List<String> typeLabels() {
        final var labels = new ArrayList<String>();
        for (final var type : IndexKind.scalarIndexClasses()) {
            labels.add(IndexKind.fileLabel(type));
        }
        for (final var kind : IndexKind.HASH_KINDS) {
            labels.add(kind.label());
        }
        return labels;
    }

    static Map<String, Set<String>> snapshot(String fieldName) throws IOException {
        final var byType = new TreeMap<String, Set<String>>();
        for (final var label : typeLabels()) {
            final var file = indexFile(fieldName, label);
            if (!file.exists()) {
                continue;
            }
            final var lines = new TreeSet<String>();
            for (final var line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                final var tokens = line.split(Globals.ID_SEPARATOR, -1);
                final var ids = Arrays.copyOfRange(tokens, 1, tokens.length);
                Arrays.sort(ids);
                lines.add(tokens[0] + " => " + String.join(",", ids));
            }
            byType.put(label, lines);
        }
        return byType;
    }

    String lookupMismatch(String fieldName, int pool) throws IOException {
        final var lookup = cache.fieldIndexLookup(DB, COLL, fieldName);
        if (lookup == null) {
            return null;
        }
        final var expected = placementsOnDisk(fieldName);
        for (var i = 0; i < pool; i++) {
            final var id = "id" + i;
            final var actual = new TreeSet<String>();
            for (var at = lookup.locate(id); at != null; at = at.next()) {
                actual.add(at.typeIdentifier() + "=" + FieldIndexEntry.fileKeyOf(at.entry().getValue()));
            }
            final var onDisk = expected.getOrDefault(id, new TreeSet<>());
            if (!actual.equals(onDisk)) {
                return id + " is " + actual + " in the cached lookup but " + onDisk + " on disk";
            }
        }
        return null;
    }

    private static Map<String, Set<String>> placementsOnDisk(String fieldName) throws IOException {
        final var identifiers = new TreeMap<String, String>();
        for (final var type : IndexKind.scalarIndexClasses()) {
            identifiers.put(IndexKind.fileLabel(type), Cache.getIndexIdentifier(fieldName, type));
        }
        for (final var kind : IndexKind.HASH_KINDS) {
            identifiers.put(kind.label(), Cache.getHashIndexIdentifier(fieldName, kind.label()));
        }
        final var placements = new TreeMap<String, Set<String>>();
        for (final var label : identifiers.entrySet()) {
            final var file = indexFile(fieldName, label.getKey());
            if (!file.exists()) {
                continue;
            }
            for (final var line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                final var tokens = line.split(Globals.ID_SEPARATOR, -1);
                for (var i = 1; i < tokens.length; i++) {
                    placements.computeIfAbsent(FieldIndexEntry.unescapeIndexToken(tokens[i]), _ -> new TreeSet<>())
                            .add(label.getValue() + "=" + tokens[0]);
                }
            }
        }
        return placements;
    }

    void admitEveryType(String fieldName) throws IOException, InterruptedException {
        locks.lock(DB, COLL);
        try {
            for (final var type : IndexKind.scalarIndexClasses()) {
                cache.getFieldIndexAndLoadIfNecessary(DB, COLL, fieldName, type);
            }
            for (final var kind : IndexKind.HASH_KINDS) {
                cache.getHashIndexAndLoadIfNecessary(DB, COLL, fieldName, kind);
            }
        } finally {
            locks.release(DB, COLL);
        }
    }

    List<FieldIndexEntry<?>> resident(String fieldName, Class<?> type) {
        return cache.residentFieldIndex(DB, COLL, Cache.getIndexIdentifier(fieldName, type));
    }

    List<FieldIndexEntry<?>> resident(String fieldName, IndexKind kind) {
        return cache.residentFieldIndex(DB, COLL, Cache.getHashIndexIdentifier(fieldName, kind.label()));
    }

    String residentMismatch(String fieldName) throws IOException {
        for (final var type : IndexKind.scalarIndexClasses()) {
            final var fresh = fs.readWholeFieldIndexFiles(DB, COLL, fieldName, type);
            final var mismatch = mismatchOf(type.getSimpleName(), resident(fieldName, type), fresh,
                    FieldIndexLoader.orderFor(type));
            if (mismatch != null) {
                return mismatch;
            }
        }
        for (final var kind : IndexKind.HASH_KINDS) {
            final var fresh = fs.readWholeHashIndexFile(DB, COLL, fieldName, kind);
            final var mismatch = mismatchOf(kind.label(), resident(fieldName, kind), fresh,
                    FieldIndexLoader.hashOrder());
            if (mismatch != null) {
                return mismatch;
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String mismatchOf(String label, List<FieldIndexEntry<?>> resident, List<?> fresh,
            java.util.Comparator order) {
        if (resident == null) {
            return null;
        }
        if (fresh == null) {
            return label + " is resident but has no file";
        }
        if (!resident.equals(fresh)) {
            return label + " resident " + resident + " differs from the file " + fresh;
        }
        for (var i = 1; i < resident.size(); i++) {
            if (order.compare(resident.get(i - 1), resident.get(i)) > 0) {
                return label + " resident list is not sorted at " + i;
            }
        }
        return null;
    }
}

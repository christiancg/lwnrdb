package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.techhouse.data.IndexKind;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.IndexFileLookup;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.utils.RangeBounds;

final class IndexFileReads {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final IndexIdCounts idCounts = IocContainer.get(IndexIdCounts.class);

    private IndexFileReads() {
    }

    static boolean fromFile(UserCache userCache, String dbName, String collName, String fieldName, Class<?> type) {
        if (!IndexFileLookup.supports(type)) {
            return false;
        }
        final var identifier = Cache.memoizedIndexIdentifier(fieldName, type);
        return userCache.residentFieldIndex(dbName, collName, identifier) == null
                && cache.isFieldIndexTypeRefused(dbName, collName, identifier)
                && shouldServeFromFile(dbName, collName, identifier);
    }

    private static boolean shouldServeFromFile(String dbName, String collName, String indexIdentifier) {
        return !cache.displacesColderIndexes(dbName, collName, indexIdentifier);
    }

    static boolean loadsTransiently(UserCache userCache, String dbName, String collName, String fieldName) {
        for (final var type : IndexKind.scalarIndexClasses()) {
            final var identifier = Cache.memoizedIndexIdentifier(fieldName, type);
            if (userCache.residentFieldIndex(dbName, collName, identifier) == null
                    && !cache.isFieldIndexTypeAbsent(dbName, collName, identifier)
                    && loadsTransiently(userCache, dbName, collName, fieldName, type, identifier)) {
                return true;
            }
        }
        return false;
    }

    private static boolean loadsTransiently(UserCache userCache, String dbName, String collName, String fieldName,
            Class<?> type, String identifier) {
        if (cache.isFieldIndexTypeRefused(dbName, collName, identifier)) {
            return true;
        }
        final var bytes = fs.indexFileBytes(dbName, collName, fieldName, IndexKind.fileLabel(type));
        return bytes > 0 && !userCache.loadsAdmitted(dbName, collName, AccessKind.FIELD_INDEX,
                CacheSizeEstimator.estimateFieldIndexSizeFromFileBytes(bytes));
    }

    static boolean typeExists(UserCache userCache, String dbName, String collName, String fieldName, Class<?> type)
            throws IOException {
        final var identifier = Cache.memoizedIndexIdentifier(fieldName, type);
        if (userCache.residentFieldIndex(dbName, collName, identifier) != null) {
            return true;
        }
        return cache.isFieldIndexTypeRefused(dbName, collName, identifier)
                ? fs.indexTypeFileExists(dbName, collName, fieldName, IndexKind.fileLabel(type))
                : userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, type) != null;
    }

    static boolean kindExists(UserCache userCache, String dbName, String collName, String fieldName, IndexKind kind)
            throws IOException {
        final var identifier = Cache.memoizedHashIndexIdentifier(fieldName, kind);
        if (userCache.residentFieldIndex(dbName, collName, identifier) != null) {
            return true;
        }
        return cache.isFieldIndexTypeRefused(dbName, collName, identifier)
                ? fs.indexTypeFileExists(dbName, collName, fieldName, kind.label())
                : userCache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, kind) != null;
    }

    static Set<String> anyOfIds(String dbName, String collName, String fieldName, Class<?> type, List<?> values)
            throws IOException {
        return fs.indexFiles().equalsIds(dbName, collName, fieldName, type, values);
    }

    static Set<String> windowIds(String dbName, String collName, String fieldName, Class<?> type,
            FieldOperatorType opType, Object value) throws IOException {
        return switch (opType) {
            case EQUALS -> anyOfIds(dbName, collName, fieldName, type, List.of(value));
            case GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS ->
                fs.indexFiles().rangeIds(dbName, collName, fieldName, type, List.of(boundOf(opType, value)));
            default -> null;
        };
    }

    static Set<String> rangeIds(String dbName, String collName, RangeBounds bounds) throws IOException {
        return fs.indexFiles().rangeIds(dbName, collName, bounds.fieldName(), Number.class, fileBounds(bounds));
    }

    static OptionalLong rangeCount(String dbName, String collName, RangeBounds bounds) throws IOException {
        return known(
                fs.indexFiles().rangeCount(dbName, collName, bounds.fieldName(), Number.class, fileBounds(bounds)));
    }

    static OptionalLong anyOfCount(String dbName, String collName, String fieldName, Class<?> type, List<?> values)
            throws IOException {
        return known(fs.indexFiles().equalsCount(dbName, collName, fieldName, type, values));
    }

    private static OptionalLong known(long count) {
        return count < 0 ? OptionalLong.empty() : OptionalLong.of(count);
    }

    private static List<IndexFileLookup.Bound> fileBounds(RangeBounds bounds) {
        final var fileBounds = new ArrayList<IndexFileLookup.Bound>(bounds.bounds().size());
        for (final var bound : bounds.bounds()) {
            fileBounds.add(boundOf(bound.operatorType(), bound.value()));
        }
        return fileBounds;
    }

    static Set<String> complementIds(String dbName, String collName, String fieldName, Class<?> type, List<?> values)
            throws IOException {
        return fs.indexFiles().complementIds(dbName, collName, fieldName, type, values);
    }

    static long idCount(String dbName, String collName, String fieldName, Class<?> type) throws IOException {
        return idCounts.count(dbName, collName, Cache.memoizedIndexIdentifier(fieldName, type),
                () -> fs.indexFiles().idCount(dbName, collName, fieldName, type));
    }

    private static IndexFileLookup.Bound boundOf(FieldOperatorType opType, Object value) {
        return switch (opType) {
            case GREATER_THAN -> new IndexFileLookup.Bound(value, true, false);
            case GREATER_THAN_EQUALS -> new IndexFileLookup.Bound(value, true, true);
            case SMALLER_THAN -> new IndexFileLookup.Bound(value, false, false);
            case SMALLER_THAN_EQUALS -> new IndexFileLookup.Bound(value, false, true);
            default -> throw new IllegalArgumentException("Not a range bound: " + opType);
        };
    }
}

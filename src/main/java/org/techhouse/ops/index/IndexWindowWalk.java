package org.techhouse.ops.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IdSet;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.fs.FieldIndexLoader;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.SearchUtils;

public final class IndexWindowWalk implements Iterator<String> {
    private static final int MAX_PAGE = 4096;
    private static final int UNKNOWN_MODIFICATIONS = -1;
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);

    private final String dbName;
    private final String collName;
    private final String fieldName;
    private final Class<Object> type;
    private final FieldOperatorType operatorType;
    private final List<Object> operands;
    private final Comparator<FieldIndexEntry<Object>> order;
    private final Set<String> emitted = new HashSet<>();
    private int pageSize;
    private List<String> page = new ArrayList<>();
    private int at;
    private boolean exhausted;
    private FieldIndexEntry<Object> cursor;
    private int cursorOffset;
    private int cursorModifications;

    @SuppressWarnings("unchecked")
    private IndexWindowWalk(String dbName, String collName, FieldOperator operator, List<Object> operands,
            int firstPage) {
        this.dbName = dbName;
        this.collName = collName;
        this.fieldName = operator.getField();
        this.operatorType = operator.getFieldOperatorType();
        this.operands = operands;
        this.type = (Class<Object>) IndexKind.scalarIndexClassOf(operands.getFirst());
        this.order = FieldIndexLoader.orderFor(type);
        this.pageSize = firstPage;
    }

    public static IndexWindowWalk open(FieldOperator operator, String dbName, String collName, int firstPage,
            ReadObserver observer) throws IOException {
        final var fieldName = operator.getField();
        if (!rl.holdsCollectionLock(dbName, collName) || cache.hasNoIndex(dbName, collName, fieldName)) {
            return null;
        }
        final var operands = operandsOf(operator);
        if (operands == null) {
            return null;
        }
        final var walk = new IndexWindowWalk(dbName, collName, operator, operands, Math.clamp(firstPage, 1, MAX_PAGE));
        for (final var id : PendingWriteReconciler.pendingIds(dbName, collName)) {
            if (walk.emitted.add(id)) {
                walk.page.add(id);
            }
        }
        if (!walk.readPage()) {
            return null;
        }
        if (!Globals.ADMIN_DB_NAME.equals(dbName)) {
            cache.recordFieldIndexAccess(dbName, collName, fieldName);
        }
        observer.indexUsed(fieldName, AnalyzeContext.fieldLockId(dbName, collName, fieldName));
        return walk;
    }

    private static List<Object> operandsOf(FieldOperator operator) {
        final var value = operator.getValue();
        return switch (operator.getFieldOperatorType()) {
            case EQUALS -> scalarOperands(List.of(value));
            case GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS -> {
                final var bound = scalarOperands(List.of(value));
                yield bound != null && (bound.getFirst() instanceof Number || bound.getFirst() instanceof JsonCustom<?>)
                        ? bound
                        : null;
            }
            case IN -> value.isJsonArray() && !value.asJsonArray().isEmpty()
                    ? scalarOperands(value.asJsonArray().asList())
                    : null;
            default -> null;
        };
    }

    private static List<Object> scalarOperands(List<? extends JsonBaseElement> values) {
        final var operands = new ArrayList<>(values.size());
        Class<?> commonType = null;
        for (final var element : values) {
            final var operand = IndexValueCodec.elementToLookupValue(element);
            if (operand == null) {
                return null;
            }
            final var operandType = IndexKind.scalarIndexClassOf(operand);
            if (operandType == null || commonType != null && commonType != operandType) {
                return null;
            }
            commonType = operandType;
            operands.add(operand);
        }
        return operands;
    }

    @Override
    public boolean hasNext() {
        while (at >= page.size()) {
            if (exhausted) {
                return false;
            }
            if (pageSize < MAX_PAGE) {
                pageSize = Math.min(pageSize * 2, MAX_PAGE);
            }
            page = new ArrayList<>();
            at = 0;
            try {
                readPage();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return true;
    }

    @Override
    public String next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return page.get(at++);
    }

    private boolean readPage() throws IOException {
        try {
            rl.lockIndexRead(dbName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            final var entries = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, type);
            if (entries == null) {
                exhausted = true;
                return false;
            }
            final List<?> cached = cache.residentFieldIndex(dbName, collName,
                    Cache.getIndexIdentifier(fieldName, type));
            final var resident = cached == entries;
            collect(entries, runsOf(entries), resident ? page.size() + pageSize : Integer.MAX_VALUE);
            return true;
        } finally {
            rl.releaseIndexRead(dbName, collName, fieldName);
        }
    }

    private List<int[]> runsOf(List<FieldIndexEntry<Object>> entries) {
        if (operatorType == FieldOperatorType.IN) {
            return SearchUtils.membershipRuns(entries, operands);
        }
        final var window = SearchUtils.windowOf(entries, operatorType, operands.getFirst());
        return window == null ? List.of() : List.of(window);
    }

    private void collect(List<FieldIndexEntry<Object>> entries, List<int[]> runs, int limit) {
        final var resumeAt = cursor == null ? 0 : lowerBound(entries, cursor);
        for (final var run : runs) {
            for (var index = Math.max(run[0], resumeAt); index < run[1]; index++) {
                if (!collectEntry(entries.get(index), limit)) {
                    return;
                }
            }
        }
        exhausted = true;
    }

    private boolean collectEntry(FieldIndexEntry<Object> entry, int limit) {
        final var ids = entry.getIds();
        final var modifications = modificationsOf(ids);
        final var skip = entry == cursor && modifications != UNKNOWN_MODIFICATIONS
                && modifications == cursorModifications ? cursorOffset : 0;
        var offset = 0;
        for (final var id : ids) {
            if (offset++ < skip) {
                continue;
            }
            if (emitted.add(id)) {
                page.add(id);
            }
            if (page.size() >= limit) {
                cursor = entry;
                cursorOffset = offset;
                cursorModifications = modifications;
                return false;
            }
        }
        return true;
    }

    private static int modificationsOf(Set<String> ids) {
        return ids instanceof IdSet idSet ? idSet.modifications() : UNKNOWN_MODIFICATIONS;
    }

    private int lowerBound(List<FieldIndexEntry<Object>> entries, FieldIndexEntry<Object> probe) {
        var low = 0;
        var high = entries.size();
        while (low < high) {
            final var middle = (low + high) >>> 1;
            if (order.compare(entries.get(middle), probe) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}

package org.techhouse.ops.tx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

final class ReplayFence {
    private static final Logger logger = Logger.logFor(ReplayFence.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final String OBJECTS_FIELD = "objects";
    private static final int MULTI_OP = 2;

    private record IdWrites(int ops, List<JsonObject> payloads) {
    }

    private ReplayFence() {
    }

    static Set<String> fencedIds(List<AdminTransactionEntry> ops, Transaction reconstructed, long preparedVersion)
            throws IOException {
        final var fenced = new HashSet<String>();
        if (preparedVersion <= 0) {
            return fenced;
        }
        final var writes = writesById(ops);
        for (final var collId : reconstructed.touchedCollections()) {
            final var overlay = reconstructed.overlayFor(collId);
            if (overlay == null) {
                continue;
            }
            final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            for (final var id : overlay.keySet()) {
                final var key = TransactionRecovery.fenceKey(parts[0], parts[1], id);
                if (isForeignWrite(parts[0], parts[1], id, preparedVersion, writes.get(key))) {
                    fenced.add(key);
                }
            }
        }
        return fenced;
    }

    private static boolean isForeignWrite(String dbName, String collName, String id, long preparedVersion,
            IdWrites writes) throws IOException {
        final var pkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var found = Collections.binarySearch(pkIndex, id);
        if (found < 0 || pkIndex.get(found).getVersion() <= preparedVersion) {
            return false;
        }
        return writes == null || writes.ops() < MULTI_OP
                || !storedEqualsOwnPayload(dbName, collName, pkIndex.get(found), writes);
    }

    private static boolean storedEqualsOwnPayload(String dbName, String collName, PkIndexEntry pkEntry,
            IdWrites writes) {
        try {
            final var stored = cache.getById(dbName, collName, pkEntry);
            return stored != null && writes.payloads().contains(stored.getData());
        } catch (Exception e) {
            logger.warning("Could not read " + pkEntry.getValue() + " in " + dbName + "|" + collName
                    + " to tell this transaction's own write from a foreign one: " + e.getMessage());
            return false;
        }
    }

    private static Map<String, IdWrites> writesById(List<AdminTransactionEntry> ops) {
        final var result = new HashMap<String, IdWrites>();
        for (final var op : ops) {
            final var dbName = op.getTargetDb();
            final var collName = op.getTargetColl();
            switch (op.getOpType()) {
                case AdminTransactionEntry.OP_TYPE_SAVE -> record(result, dbName, collName, op.getPayload(), true);
                case AdminTransactionEntry.OP_TYPE_BULK_SAVE -> {
                    for (final var element : op.getPayload().get(OBJECTS_FIELD).asJsonArray().asList()) {
                        record(result, dbName, collName, element.asJsonObject(), true);
                    }
                }
                case AdminTransactionEntry.OP_TYPE_DELETE -> record(result, dbName, collName, op.getPayload(), false);
                default -> {
                }
            }
        }
        return result;
    }

    private static void record(Map<String, IdWrites> result, String dbName, String collName, JsonObject payload,
            boolean isSave) {
        final var id = payload.get(Globals.PK_FIELD).asJsonString().getValue();
        final var key = TransactionRecovery.fenceKey(dbName, collName, id);
        final var previous = result.get(key);
        final var payloads = previous == null ? new ArrayList<JsonObject>() : previous.payloads();
        if (isSave) {
            payloads.add(payload);
        }
        result.put(key, new IdWrites(previous == null ? 1 : previous.ops() + 1, payloads));
    }
}

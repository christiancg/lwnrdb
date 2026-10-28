package org.techhouse.cluster;

import java.util.List;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.msg.TxReplicationPayload;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.validations.NameValidations;

public final class PeerNames {
    private PeerNames() {
    }

    public static boolean collection(String dbName, String collName) {
        return NameValidations.isName(dbName) && NameValidations.isName(collName)
                && !NameValidations.isReservedDbName(dbName);
    }

    public static boolean payload(ReplicationPayload payload) {
        if (payload == null || payload.getOp() == null || !collection(payload.getDbName(), payload.getCollName())) {
            return false;
        }
        return payload.getOp() == ReplicationOp.UPSERT ? documents(payload.getDocuments()) : ids(payload.getIds());
    }

    public static boolean txPayload(TxReplicationPayload payload) {
        if (payload == null || payload.getEntries().isEmpty()) {
            return false;
        }
        for (final var entry : payload.getEntries()) {
            if (!payload(entry)) {
                return false;
            }
        }
        return true;
    }

    public static boolean antiEntropy(AntiEntropyPayload payload) {
        return payload != null && collection(payload.getDbName(), payload.getCollName()) && buckets(payload);
    }

    private static boolean buckets(AntiEntropyPayload payload) {
        final var bucketCount = payload.getBucketCount();
        if (bucketCount == 0) {
            return payload.getBuckets() == null;
        }
        if (!DigestHash.isBucketCount(bucketCount)) {
            return false;
        }
        if (payload.getBuckets() == null) {
            return true;
        }
        if (payload.getBuckets().size() > bucketCount) {
            return false;
        }
        for (final var bucket : payload.getBuckets()) {
            if (bucket == null || bucket < 0 || bucket >= bucketCount) {
                return false;
            }
        }
        return true;
    }

    private static boolean documents(List<JsonObject> documents) {
        if (documents == null) {
            return false;
        }
        for (final var document : documents) {
            if (document == null || !(document.get(Globals.PK_FIELD) instanceof JsonString id)
                    || !NameValidations.isId(id.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean ids(List<String> ids) {
        if (ids == null) {
            return false;
        }
        for (final var id : ids) {
            if (!NameValidations.isId(id)) {
                return false;
            }
        }
        return true;
    }
}

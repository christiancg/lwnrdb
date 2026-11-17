package org.techhouse.ops;

import java.io.IOException;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;

final class InsertOnlyGuard {
    private static final Cache cache = IocContainer.get(Cache.class);

    private InsertOnlyGuard() {
    }

    static OperationResponse check(SaveRequest request) throws IOException {
        if (!request.isFailOnUpdate()) {
            return null;
        }
        final var id = unambiguousId(request);
        if (id == null) {
            return null;
        }
        final var rows = cache.pkRowsForWriter(request.getDatabaseName(), request.getCollectionName(), List.of(id));
        return rows.row(id) == null
                ? null
                : new OperationResponse(OperationType.SAVE, ErrorCode.DOCUMENT_ALREADY_EXISTS);
    }

    private static String unambiguousId(SaveRequest request) {
        final var requestId = request.get_id();
        final var objectId = request.getObject().get(Globals.PK_FIELD) instanceof JsonString jsonString
                ? jsonString.getValue()
                : null;
        if (objectId == null) {
            return requestId;
        }
        return requestId == null || requestId.equals(objectId) ? objectId : null;
    }
}

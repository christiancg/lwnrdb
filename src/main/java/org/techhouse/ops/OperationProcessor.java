package org.techhouse.ops;

import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.*;
import org.techhouse.ops.resp.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OperationProcessor {
    private static final String PRIMARY_KEY_FIELD_NAME = "_id";
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Map<String, Map<String, List<IndexEntry>>> indexMap = new ConcurrentHashMap<>();
    private final Map<String, List<DbEntry>> collectionMap = new ConcurrentHashMap<>();

    public OperationResponse processMessage(OperationRequest operationRequest) {
        return switch (operationRequest.getType()) {
            case SAVE -> processSaveOperation((SaveRequest) operationRequest);
            case FIND -> processFindOperation((FindRequest) operationRequest);
            case AGGREGATE -> processAggregateOperation((AggregateRequest) operationRequest);
            case DELETE -> processDeleteOperation((DeleteRequest) operationRequest);
            case CREATE_DATABASE -> processCreateDatabaseOperation((CreateDatabaseRequest) operationRequest);
            case CREATE_COLLECTION -> processCreateCollectionOperation((CreateCollectionRequest) operationRequest);
        };
    }

    private SaveResponse processSaveOperation(SaveRequest saveRequest) {
        final var dbName = saveRequest.getDatabaseName();
        final var collName = saveRequest.getCollectionName();
        final var entry = DbEntry.fromJsonObject(dbName, collName, saveRequest.getObject());
        try {
            final var fieldMapName = dbName + '|' + collName;
            var indexedFields = indexMap.get(fieldMapName);
            List<IndexEntry> primaryKeyIndex;
            if (indexedFields != null) {
                primaryKeyIndex = indexedFields.get(PRIMARY_KEY_FIELD_NAME);
                if (primaryKeyIndex == null) {
                    primaryKeyIndex = fs.readWholeIndexFile(dbName, collName, PRIMARY_KEY_FIELD_NAME);
                    indexedFields.put(PRIMARY_KEY_FIELD_NAME, primaryKeyIndex);
                }
            } else {
                primaryKeyIndex = fs.readWholeIndexFile(dbName, collName, PRIMARY_KEY_FIELD_NAME);
                indexedFields = new ConcurrentHashMap<>();
                indexedFields.put(PRIMARY_KEY_FIELD_NAME, primaryKeyIndex);
                indexMap.put(fieldMapName, indexedFields);
            }
            final var foundIndexEntry = primaryKeyIndex.stream().filter(indexEntry -> indexEntry.getValue().equals(saveRequest.get_id())).findFirst();
            IndexEntry savedIndexEntry;
            if (foundIndexEntry.isPresent()) {
                savedIndexEntry = fs.updateFromCollection(entry, foundIndexEntry.get());
                primaryKeyIndex.remove(foundIndexEntry.get());
            } else {
                savedIndexEntry = fs.insertIntoCollection(entry);
            }
            primaryKeyIndex.add(savedIndexEntry);
            return new SaveResponse(OperationStatus.OK, "Successfully saved", savedIndexEntry.getValue());
        } catch (Exception exception) {
            return new SaveResponse(OperationStatus.ERROR, "Error while saving entry: " + exception.getMessage(), null);
        }
    }

    private FindResponse processFindOperation(FindRequest findRequest) {
        return new FindResponse(OperationStatus.OK, "dummy");
    }

    private AggregateResponse processAggregateOperation(AggregateRequest aggregateRequest) {
        return new AggregateResponse(OperationStatus.OK, "dummy");
    }

    private DeleteResponse processDeleteOperation(DeleteRequest deleteRequest) {
        return new DeleteResponse(OperationStatus.OK, "dummy");
    }

    private CreateDatabaseResponse processCreateDatabaseOperation(CreateDatabaseRequest createDatabaseRequest) {
        final var result = fs.createDatabaseFolder(createDatabaseRequest.getDatabaseName());
        if (result) {
            return new CreateDatabaseResponse(OperationStatus.OK, "Database created successfully");
        }
        return new CreateDatabaseResponse(OperationStatus.ERROR, "Error while creating database");
    }

    private CreateCollectionResponse processCreateCollectionOperation(CreateCollectionRequest createCollectionRequest) {
        try {
            final var result = fs.createCollectionFile(createCollectionRequest.getDatabaseName(), createCollectionRequest.getCollectionName());
            if (result) {
                return new CreateCollectionResponse(OperationStatus.OK, "Collection created successfully");
            }
            return new CreateCollectionResponse(OperationStatus.ERROR, "Error while creating collection");
        } catch (Exception e) {
            return new CreateCollectionResponse(OperationStatus.ERROR, "Error while creating collection: " + e.getMessage());
        }
    }
}

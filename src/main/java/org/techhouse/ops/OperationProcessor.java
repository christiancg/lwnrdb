package org.techhouse.ops;

import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.*;
import org.techhouse.ops.resp.*;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class OperationProcessor {
    private static final String PK_FIELD_NAME = "_id";
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Map<String, Map<String, List<IndexEntry>>> indexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DbEntry>> collectionMap = new ConcurrentHashMap<>();

    public OperationResponse processMessage(OperationRequest operationRequest) {
        return switch (operationRequest.getType()) {
            case SAVE -> processSaveOperation((SaveRequest) operationRequest);
            case FIND_BY_ID -> processFindByIdOperation((FindByIdRequest) operationRequest);
            case AGGREGATE -> processAggregateOperation((AggregateRequest) operationRequest);
            case DELETE -> processDeleteOperation((DeleteRequest) operationRequest);
            case CREATE_DATABASE -> processCreateDatabaseOperation((CreateDatabaseRequest) operationRequest);
            case CREATE_COLLECTION -> processCreateCollectionOperation((CreateCollectionRequest) operationRequest);
            case CLOSE_CONNECTION -> new CloseConnectionResponse();
        };
    }

    public static String getCollectionIdentifier(String dbName, String collName) {
        return dbName + '|' + collName;
    }

    private List<IndexEntry> getIdIndexAndLoadIfNecessary(String dbName, String collName) throws ExecutionException, InterruptedException {
        final var fieldMapName = getCollectionIdentifier(dbName, collName);
        var indexedFields = indexMap.get(fieldMapName);
        List<IndexEntry> primaryKeyIndex;
        if (indexedFields != null) {
            primaryKeyIndex = indexedFields.get(PK_FIELD_NAME);
            if (primaryKeyIndex == null) {
                primaryKeyIndex = fs.readWholeIndexFile(dbName, collName, PK_FIELD_NAME);
                indexedFields.put(PK_FIELD_NAME, primaryKeyIndex);
            }
        } else {
            primaryKeyIndex = fs.readWholeIndexFile(dbName, collName, PK_FIELD_NAME);
            indexedFields = new ConcurrentHashMap<>();
            indexedFields.put(PK_FIELD_NAME, primaryKeyIndex);
            indexMap.put(fieldMapName, indexedFields);
        }
        return primaryKeyIndex;
    }

    private SaveResponse processSaveOperation(SaveRequest saveRequest) {
        final var dbName = saveRequest.getDatabaseName();
        final var collName = saveRequest.getCollectionName();
        final var entry = DbEntry.fromJsonObject(dbName, collName, saveRequest.getObject());
        try {
            final var primaryKeyIndex = getIdIndexAndLoadIfNecessary(saveRequest.getDatabaseName(), saveRequest.getCollectionName());
            var foundIndexEntry = -1;
            if (saveRequest.get_id() != null) {
                foundIndexEntry = Collections.binarySearch(primaryKeyIndex, saveRequest.get_id());
            }
            IndexEntry savedIndexEntry;
            if (foundIndexEntry >= 0) {
                final var idxEntry = primaryKeyIndex.get(foundIndexEntry);
                savedIndexEntry = fs.updateFromCollection(entry, idxEntry);
                primaryKeyIndex.remove(idxEntry);
            } else {
                savedIndexEntry = fs.insertIntoCollection(entry);
            }
            primaryKeyIndex.add(savedIndexEntry);
            primaryKeyIndex.sort(Comparator.comparing(IndexEntry::getValue));
            final var collId = getCollectionIdentifier(dbName, collName);
            var coll = collectionMap.get(collId);
            if (coll == null) {
                coll = new ConcurrentHashMap<>();
                collectionMap.put(collId, coll);
            }
            coll.put(entry.get_id(), entry);
            return new SaveResponse(OperationStatus.OK, "Successfully saved", savedIndexEntry.getValue());
        } catch (Exception exception) {
            return new SaveResponse(OperationStatus.ERROR, "Error while saving entry: " + exception.getMessage(), null);
        }
    }

    private FindByIdResponse processFindByIdOperation(FindByIdRequest findbyIdRequest) {
        final var dbName = findbyIdRequest.getDatabaseName();
        final var collName = findbyIdRequest.getCollectionName();
        final var id = findbyIdRequest.get_id();
        try {
            final var primaryKeyIndex = getIdIndexAndLoadIfNecessary(dbName, collName);
            final var foundIndexEntry = Collections.binarySearch(primaryKeyIndex, id);
            if (foundIndexEntry >= 0) {
                final var primaryKeyIndexEntry = primaryKeyIndex.get(foundIndexEntry);
                final var primaryKey = primaryKeyIndexEntry.getValue();
                final var collectionMapName = getCollectionIdentifier(dbName, collName);
                final var coll = collectionMap.computeIfAbsent(collectionMapName, k -> new ConcurrentHashMap<>());
                var entry = coll.get(primaryKey);
                if (entry == null) {
                    entry = fs.getById(primaryKeyIndexEntry);
                    coll.put(primaryKey, entry);
                }
                return new FindByIdResponse(OperationStatus.OK, "Ok", entry.getData());
            } else {
                return new FindByIdResponse(OperationStatus.NOT_FOUND, "Not found", null);
            }
        } catch (Exception exception) {
            return new FindByIdResponse(OperationStatus.ERROR, "Error while retrieving entry: " + exception.getMessage(), null);
        }
    }

    private AggregateResponse processAggregateOperation(AggregateRequest aggregateRequest) {
        try {
            final var results = AggregationOperationHelper.processAggregation(aggregateRequest, indexMap, collectionMap);
            return results.isEmpty() ?
                    new AggregateResponse(OperationStatus.NOT_FOUND, "No results", null) :
                    new AggregateResponse(OperationStatus.OK, "Ok", results);
        } catch (Exception e) {
            return new AggregateResponse(OperationStatus.ERROR, "An error occurred while processing the aggregation: " + e.getMessage(), null);
        }
    }

    private DeleteResponse processDeleteOperation(DeleteRequest deleteRequest) {
        try {
            final var primaryKeyIndex = getIdIndexAndLoadIfNecessary(deleteRequest.getDatabaseName(), deleteRequest.getCollectionName());
            final var foundIndexEntry = primaryKeyIndex.stream().filter(indexEntry -> indexEntry.getValue().equals(deleteRequest.get_id())).findFirst();
            if (foundIndexEntry.isPresent()) {
                final var idxEntry = foundIndexEntry.get();
                fs.deleteFromCollection(idxEntry);
                primaryKeyIndex.remove(idxEntry);
                primaryKeyIndex.sort(Comparator.comparing(IndexEntry::getValue));
                return new DeleteResponse(OperationStatus.OK, "Entry with id " + deleteRequest.get_id() + " deleted successfully");
            } else {
                return new DeleteResponse(OperationStatus.NOT_FOUND, "Entry with id " + deleteRequest.get_id() + " not found");
            }
        } catch (Exception exception) {
            return new DeleteResponse(OperationStatus.ERROR, "Error while deleting entry with id: " + deleteRequest.get_id() + ". Error message: " + exception.getMessage());
        }
    }

    private CreateDatabaseResponse processCreateDatabaseOperation(CreateDatabaseRequest createDatabaseRequest) {
        try {
            final var result = fs.createDatabaseFolder(createDatabaseRequest.getDatabaseName());
            if (result) {
                return new CreateDatabaseResponse(OperationStatus.OK, "Database created successfully");
            }
            return new CreateDatabaseResponse(OperationStatus.ERROR, "Error while creating database");
        } catch (Exception exception) {
            return new CreateDatabaseResponse(OperationStatus.ERROR, "Error while creating database");
        }
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

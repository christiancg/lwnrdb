import type { Filter, PipelineStep } from "../query/types.js";
import { PipelineEncoder } from "./pipeline-encoder.js";
import { encodeDocument, type EncodeOptions, type JsonObject } from "./values-codec.js";

export function request(type: string, database?: string, collection?: string): JsonObject {
    return {
        type,
        ...(database === undefined ? {} : { databaseName: database }),
        ...(collection === undefined ? {} : { collectionName: collection }),
    };
}

function dirty(frame: JsonObject, dirtyRead: boolean): JsonObject {
    return dirtyRead ? { ...frame, dirtyRead: true } : frame;
}

export class RequestEncoder {
    readonly pipeline: PipelineEncoder;
    readonly #options: EncodeOptions;

    constructor(options: EncodeOptions = {}) {
        this.#options = options;
        this.pipeline = new PipelineEncoder(options);
    }

    document(value: unknown): JsonObject {
        return encodeDocument(value, this.#options);
    }

    authenticate(username: string, password: string): JsonObject {
        return { ...request("AUTHENTICATE"), username, password };
    }

    save(database: string, collection: string, document: JsonObject): JsonObject {
        return { ...request("SAVE", database, collection), object: document };
    }

    bulkSave(database: string, collection: string, documents: JsonObject[]): JsonObject {
        return { ...request("BULK_SAVE", database, collection), objects: documents };
    }

    findById(database: string, collection: string, id: string, dirtyRead: boolean): JsonObject {
        return dirty({ ...request("FIND_BY_ID", database, collection), _id: id }, dirtyRead);
    }

    delete(database: string, collection: string, id: string): JsonObject {
        return { ...request("DELETE", database, collection), _id: id };
    }

    deleteWhere(database: string, collection: string, condition: Filter): JsonObject {
        return { ...request("DELETE", database, collection), condition: this.pipeline.filter(condition) };
    }

    updateWhere(database: string, collection: string, condition: Filter, patch: JsonObject): JsonObject {
        return { ...request("SAVE", database, collection), condition: this.pipeline.filter(condition), object: patch };
    }

    aggregate(
        database: string,
        collection: string,
        steps: readonly PipelineStep[],
        dirtyRead: boolean,
        analyze: boolean,
    ): JsonObject {
        const frame = { ...request("AGGREGATE", database, collection), aggregationSteps: this.pipeline.steps(steps) };
        return dirty(analyze ? { ...frame, analyze: true } : frame, dirtyRead);
    }

    listen(database: string, collection: string, steps: readonly PipelineStep[]): JsonObject {
        return { ...request("LISTEN", database, collection), aggregationSteps: this.pipeline.steps(steps) };
    }

    stopListen(listenId: string): JsonObject {
        return { ...request("STOP_LISTEN"), listenId };
    }

    listDatabases(): JsonObject {
        return request("LIST_DATABASES");
    }

    createDatabase(database: string): JsonObject {
        return request("CREATE_DATABASE", database);
    }

    dropDatabase(database: string): JsonObject {
        return request("DROP_DATABASE", database);
    }

    listCollections(database: string): JsonObject {
        return request("LIST_COLLECTIONS", database);
    }

    createCollection(database: string, collection: string): JsonObject {
        return request("CREATE_COLLECTION", database, collection);
    }

    dropCollection(database: string, collection: string): JsonObject {
        return request("DROP_COLLECTION", database, collection);
    }

    createIndex(database: string, collection: string, fields: readonly string[]): JsonObject {
        const frame = request("CREATE_INDEX", database, collection);
        const [only] = fields;
        return fields.length === 1 && only !== undefined
            ? { ...frame, fieldName: only }
            : { ...frame, fieldNames: [...fields] };
    }

    dropIndex(database: string, collection: string, field: string): JsonObject {
        return { ...request("DROP_INDEX", database, collection), fieldName: field };
    }

    reindex(database: string, collection: string, fields: readonly string[]): JsonObject {
        const frame = request("REINDEX", database, collection);
        return fields.length === 0 ? frame : { ...frame, fieldNames: [...fields] };
    }

    reindexPrimaryKey(database: string, collection: string): JsonObject {
        return { ...request("REINDEX", database, collection), primaryKey: true };
    }

    saveSchema(database: string, collection: string, schema: JsonObject): JsonObject {
        return { ...request("SAVE_SCHEMA", database, collection), schema };
    }

    getSchema(database: string, collection: string): JsonObject {
        return request("GET_SCHEMA", database, collection);
    }

    deleteSchema(database: string, collection: string): JsonObject {
        return request("DELETE_SCHEMA", database, collection);
    }

    startTransaction(): JsonObject {
        return request("START_TRANSACTION");
    }

    commitTransaction(): JsonObject {
        return request("COMMIT_TRANSACTION");
    }

    rollbackTransaction(): JsonObject {
        return request("ROLLBACK_TRANSACTION");
    }

    serverInfo(): JsonObject {
        return request("SERVER_INFO");
    }

    databaseStats(database?: string): JsonObject {
        return request("GET_DATABASE_STATS", database);
    }

    closeConnection(): JsonObject {
        return request("CLOSE_CONNECTION");
    }

    setDatabaseOwners(database: string, owners: readonly string[]): JsonObject {
        return { ...request("SET_DATABASE_OWNERS", database), owners: [...owners] };
    }
}

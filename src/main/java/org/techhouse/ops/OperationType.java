package org.techhouse.ops;

public enum OperationType {
    SAVE, //INSERT, UPDATE
    FIND_BY_ID,
    AGGREGATE,
    DELETE,
    CREATE_DATABASE,
    DROP_DATABASE,
    CREATE_COLLECTION,
    DROP_COLLECTION,
    CLOSE_CONNECTION
}

package org.techhouse.ops;

public enum OperationType {
    SAVE, //INSERT, UPDATE
    FIND, //AGGREGATION WITH JUST 1 STEP
    AGGREGATE,
    DELETE,
    CREATE_DATABASE,
    CREATE_COLLECTION
}

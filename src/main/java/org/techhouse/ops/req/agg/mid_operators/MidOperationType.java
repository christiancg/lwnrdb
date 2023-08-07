package org.techhouse.ops.req.agg.mid_operators;

public enum MidOperationType {
    // Number operators
    AVG, // -> Array
    SUM, // -> Array
    SUBS, // -> Array
    MAX, // -> Array
    MIN, // -> Array
    MULTIPLY, // -> Array
    DIVIDE, // -> Array
    POW, // -> Array
    ROOT, // -> Array
    ABS, // -> One param
    MOD, // -> One param
    // Array / String operators
    SIZE, // -> One param
    CONCAT, // -> Array
    // Cast
    CAST // - Custom, with fieldName and toType
}

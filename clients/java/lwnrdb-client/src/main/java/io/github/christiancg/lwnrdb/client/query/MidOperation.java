package io.github.christiancg.lwnrdb.client.query;

public sealed interface MidOperation permits ArrayOperation, FieldFunction, CastOperation, ScriptOperation {
}

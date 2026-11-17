package io.github.christiancg.lwnrdb.client.query;

public sealed interface Filter
        permits FieldFilter, ConjunctionFilter, DistanceFilter, WithinFilter, NearestFilter, ScriptFilter {
}

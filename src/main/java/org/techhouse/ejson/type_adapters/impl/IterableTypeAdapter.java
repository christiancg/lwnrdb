package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class IterableTypeAdapter<T> implements TypeAdapter<Iterable<T>> {

    private final Class<T> tClass;

    public IterableTypeAdapter(Class<T> tClass) {
        this.tClass = tClass;
    }

    @Override
    public String toJson(Iterable<T> value) {
        final var iterator = value.iterator();
        if (iterator.hasNext()) {
            return '[' +
                    StreamSupport.stream(Spliterators.spliteratorUnknownSize(value.iterator(), Spliterator.ORDERED), false)
                            .map(t ->
                                    Objects.requireNonNull(TypeAdapterFactory.getAdapter(tClass)).toJson(t)
                            )
                            .collect(Collectors.joining(","))
                    + ']';
        } else {
            return "[]";
        }
    }

    @Override
    public Iterable<T> fromJson(JsonBaseElement value) {
        if (value instanceof JsonArray) {
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(value.asJsonArray().iterator(), Spliterator.ORDERED), false)
                    .map(jsonBaseElement -> TypeAdapterFactory.getAdapter(tClass).fromJson(jsonBaseElement))
                    .toList();
        } else {
            return null;
        }
    }
}

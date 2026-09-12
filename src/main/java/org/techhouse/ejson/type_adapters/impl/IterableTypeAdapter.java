package org.techhouse.ejson.type_adapters.impl;

import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class IterableTypeAdapter<T> implements TypeAdapter<Iterable<T>> {

    private final Class<T> tClass;

    public IterableTypeAdapter(Class<T> tClass) {
        this.tClass = tClass;
    }

    @Override
    public String toJson(Iterable<T> value) {
        final var out = new StringBuilder();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(Iterable<T> value, StringBuilder out) {
        final var elementAdapter = Objects.requireNonNull(TypeAdapterFactory.getAdapter(tClass));
        out.append('[');
        var first = true;
        for (final var element : value) {
            if (!first) {
                out.append(',');
            }
            first = false;
            elementAdapter.toJson(element, out);
        }
        out.append(']');
    }

    @Override
    public Iterable<T> fromJson(JsonBaseElement value) {
        if (value instanceof JsonArray arr) {
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(arr.iterator(), Spliterator.ORDERED), false)
                    .map(jsonBaseElement -> TypeAdapterFactory.getAdapter(tClass).fromJson(jsonBaseElement)).toList();
        } else {
            return null;
        }
    }
}

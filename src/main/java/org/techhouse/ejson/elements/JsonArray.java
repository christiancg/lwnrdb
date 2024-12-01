package org.techhouse.ejson.elements;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class JsonArray extends JsonBaseElement implements Iterable<JsonBaseElement> {
    private final ArrayList<JsonBaseElement> elements = new ArrayList<>();

    public void add(JsonBaseElement element) {
        if (element == null) {
            element = JsonNull.INSTANCE;
        }
        elements.add(element);
    }

    public void addAll(JsonArray array) {
        elements.addAll(array.elements);
    }

    public void add(String string) {
        elements.add(new JsonString(string));
    }

    public JsonBaseElement set(int index, JsonBaseElement element) {
        return elements.set(index, element == null ? JsonNull.INSTANCE : element);
    }

    public boolean remove(JsonBaseElement element) {
        return elements.remove(element);
    }

    public JsonBaseElement remove(int index) {
        return elements.remove(index);
    }

    public boolean contains(JsonBaseElement element) {
        return elements.contains(element);
    }

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public JsonBaseElement get(int i) {
        return elements.get(i);
    }

    public List<JsonBaseElement> asList() {
        return new ArrayList<>(elements);
    }

    @Override
    public boolean equals(Object o) {
        return (o == this) || (o instanceof JsonArray && ((JsonArray) o).elements.equals(elements));
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    public Iterator<JsonBaseElement> iterator() {
        return elements.iterator();
    }

    @Override
    public JsonBaseElement deepCopy() {
        final var newArr = new JsonArray();
        for (final var element : elements) {
            newArr.add(element.deepCopy());
        }
        return newArr;
    }
}

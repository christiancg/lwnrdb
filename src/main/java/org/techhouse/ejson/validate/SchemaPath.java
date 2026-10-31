package org.techhouse.ejson.validate;

final class SchemaPath {
    static final SchemaPath ROOT = new SchemaPath(null, null, -1);
    private static final String ROOT_LABEL = "<root>";

    private final SchemaPath parent;
    private final String name;
    private final int index;

    private SchemaPath(SchemaPath parent, String name, int index) {
        this.parent = parent;
        this.name = name;
        this.index = index;
    }

    SchemaPath property(String propertyName) {
        return new SchemaPath(this, propertyName, -1);
    }

    SchemaPath item(int itemIndex) {
        return new SchemaPath(this, null, itemIndex);
    }

    String describe() {
        if (parent == null) {
            return ROOT_LABEL;
        }
        final var pointer = new StringBuilder();
        appendTo(pointer);
        return pointer.toString();
    }

    private void appendTo(StringBuilder pointer) {
        if (parent == null) {
            return;
        }
        parent.appendTo(pointer);
        pointer.append('/');
        if (name != null) {
            pointer.append(name);
        } else {
            pointer.append(index);
        }
    }

    @Override
    public String toString() {
        return describe();
    }
}

package org.techhouse.simplejs.values;

// A Private Name. The spec creates a fresh one for every private name a class body declares, once per
// class *evaluation*, so two classes produced by the same factory never share a slot on an object.
// Identity is therefore the whole point: equals/hashCode are deliberately left as Object's, which is
// why this is a class and not a record - a record's generated equals would make two same-named private
// names from two class evaluations compare equal and collide. The description is for error messages.
@SuppressWarnings("ClassCanBeRecord")
public final class PrivateName {
    private final String description;

    public PrivateName(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return description;
    }
}

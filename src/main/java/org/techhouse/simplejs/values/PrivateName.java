package org.techhouse.simplejs.values;

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

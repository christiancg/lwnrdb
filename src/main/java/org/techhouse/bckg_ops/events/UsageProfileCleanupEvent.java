package org.techhouse.bckg_ops.events;

public class UsageProfileCleanupEvent extends Event {
    public UsageProfileCleanupEvent() {
        super(EventType.DELETED);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof UsageProfileCleanupEvent))
            return false;
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Override
    public String toString() {
        return "UsageProfileCleanupEvent(super=" + super.toString() + ")";
    }
}

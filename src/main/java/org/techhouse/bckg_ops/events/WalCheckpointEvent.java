package org.techhouse.bckg_ops.events;

import java.util.Objects;

public class WalCheckpointEvent extends Event {
    public WalCheckpointEvent() {
        super(EventType.UPDATED);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof WalCheckpointEvent))
            return false;
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getType(), getClass());
    }

    @Override
    public String toString() {
        return "WalCheckpointEvent(super=" + super.toString() + ")";
    }
}

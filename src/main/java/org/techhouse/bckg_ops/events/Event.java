package org.techhouse.bckg_ops.events;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public abstract class Event {
    private final EventType type;
}

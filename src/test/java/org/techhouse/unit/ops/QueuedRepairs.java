package org.techhouse.unit.ops;

import java.util.Collection;
import java.util.List;
import org.techhouse.bckg_ops.BackgroundLanes;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.IndexRepairEvent;
import org.techhouse.test.TestUtils;

final class QueuedRepairs {
    private QueuedRepairs() {
    }

    static List<IndexRepairEvent> of(BackgroundTaskManager manager) throws Exception {
        final var lanes = TestUtils.getPrivateField(manager, "lanes", BackgroundLanes.class);
        final Collection<?> queue = TestUtils.getPrivateField(lanes, "maintenance", Collection.class);
        return queue.stream().filter(IndexRepairEvent.class::isInstance).map(IndexRepairEvent.class::cast).toList();
    }

    static List<String> idsOf(BackgroundTaskManager manager) throws Exception {
        return of(manager).stream().flatMap(event -> event.getIds().stream()).toList();
    }
}

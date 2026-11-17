package io.github.christiancg.lwnrdb.client.scripting;

import io.github.christiancg.lwnrdb.client.internal.ClientContext;
import io.github.christiancg.lwnrdb.client.internal.Descriptors;
import io.github.christiancg.lwnrdb.client.internal.ResponseDecoder;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import java.util.List;
import java.util.Optional;

public final class Schedules {
    private final ClientContext context;
    private final String database;

    public Schedules(ClientContext context, String database) {
        this.context = context;
        this.database = database;
    }

    public long save(ScheduleDefinition schedule) {
        return context.channel().send(Operation.SAVE_SCHEDULE, context.scripts().saveSchedule(database, schedule))
                .path("version").asLong(0);
    }

    public void delete(String name) {
        context.channel().send(Operation.DELETE_SCHEDULE, context.scripts().deleteSchedule(database, name));
    }

    public List<ScheduleInfo> list() {
        return list(Optional.empty());
    }

    public List<ScheduleInfo> list(boolean includeArgs) {
        return list(Optional.of(includeArgs));
    }

    private List<ScheduleInfo> list(Optional<Boolean> includeArgs) {
        final var reply = context.channel().send(Operation.LIST_SCHEDULES,
                context.scripts().listSchedules(database, includeArgs));
        return ResponseDecoder.documents(reply, "schedules").stream().map(Descriptors::schedule).toList();
    }
}

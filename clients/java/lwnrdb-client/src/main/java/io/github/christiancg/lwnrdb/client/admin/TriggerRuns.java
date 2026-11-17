package io.github.christiancg.lwnrdb.client.admin;

import io.github.christiancg.lwnrdb.client.internal.ClientContext;
import io.github.christiancg.lwnrdb.client.internal.Descriptors;
import io.github.christiancg.lwnrdb.client.internal.ResponseDecoder;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import java.util.List;
import java.util.Optional;

public final class TriggerRuns {
    private final ClientContext context;

    TriggerRuns(ClientContext context) {
        this.context = context;
    }

    public List<TriggerRun> list() {
        return list(Optional.empty());
    }

    public List<TriggerRun> list(TriggerRunStatus status) {
        return list(Optional.of(status));
    }

    public boolean resolve(String runId, TriggerRunDecision decision) {
        return context.channel()
                .send(Operation.RESOLVE_TRIGGER_RUN, context.scripts().resolveTriggerRun(runId, decision))
                .path("resolved").asBoolean(false);
    }

    private List<TriggerRun> list(Optional<TriggerRunStatus> status) {
        final var reply = context.channel().send(Operation.LIST_TRIGGER_RUNS,
                context.scripts().listTriggerRuns(status));
        return ResponseDecoder.documents(reply, "runs").stream().map(Descriptors::triggerRun).toList();
    }
}

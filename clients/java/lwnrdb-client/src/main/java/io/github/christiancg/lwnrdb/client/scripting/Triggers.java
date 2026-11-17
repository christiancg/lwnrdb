package io.github.christiancg.lwnrdb.client.scripting;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.internal.ClientContext;
import io.github.christiancg.lwnrdb.client.internal.Descriptors;
import io.github.christiancg.lwnrdb.client.internal.ResponseDecoder;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import java.util.List;
import java.util.Optional;

public final class Triggers {
    private final ClientContext context;
    private final String database;
    private final String collection;

    public Triggers(ClientContext context, String database, String collection) {
        this.context = context;
        this.database = database;
        this.collection = collection;
    }

    public TriggerSaved save(TriggerDefinition trigger) {
        final var reply = context.channel().send(Operation.SAVE_TRIGGER,
                context.scripts().saveTrigger(database, collection, trigger));
        return new TriggerSaved(reply.path("version").asLong(0), reply.path("definer").asString(""));
    }

    public void delete(String name) {
        context.channel().send(Operation.DELETE_TRIGGER, context.scripts().deleteTrigger(database, collection, name));
    }

    public List<TriggerInfo> list() {
        final var reply = context.channel().send(Operation.LIST_TRIGGERS,
                context.scripts().listTriggers(database, Optional.of(collection)));
        return ResponseDecoder.documents(reply, "triggers").stream().map(Descriptors::trigger).toList();
    }

    public TriggerTestResult test(String name, TriggerEvent event, Document document) {
        final var reply = context.channel().send(Operation.TEST_TRIGGER,
                context.scripts().testTrigger(database, collection, name, event, document));
        return new TriggerTestResult(reply.path("decision").asString(""), ResponseDecoder.document(reply, "document"),
                ResponseDecoder.strings(reply.get("logs")), reply.path("logsTruncated").asBoolean(false));
    }
}

package io.github.christiancg.lwnrdb.client;

import io.github.christiancg.lwnrdb.client.internal.ClientContext;
import io.github.christiancg.lwnrdb.client.internal.CollectionOps;
import io.github.christiancg.lwnrdb.client.internal.Descriptors;
import io.github.christiancg.lwnrdb.client.internal.Names;
import io.github.christiancg.lwnrdb.client.internal.ResponseDecoder;
import io.github.christiancg.lwnrdb.client.internal.ScriptResults;
import io.github.christiancg.lwnrdb.client.mapping.JacksonCodec;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import io.github.christiancg.lwnrdb.client.scripting.Procedures;
import io.github.christiancg.lwnrdb.client.scripting.Schedules;
import io.github.christiancg.lwnrdb.client.scripting.TriggerInfo;
import java.util.List;
import java.util.Optional;

public final class Database {
    private final ClientContext context;
    private final String name;

    Database(ClientContext context, String name) {
        this.context = context;
        this.name = Names.database(name);
    }

    public String name() {
        return name;
    }

    public void create() {
        context.channel().send(Operation.CREATE_DATABASE, context.encoder().createDatabase(name));
    }

    public void drop() {
        context.channel().send(Operation.DROP_DATABASE, context.encoder().dropDatabase(name));
    }

    public List<String> listCollections() {
        final var reply = context.channel().send(Operation.LIST_COLLECTIONS, context.encoder().listCollections(name));
        return ResponseDecoder.strings(reply.get("collections"));
    }

    public Collection<Document> collection(String collection) {
        return collection(collection, DocumentCodec.identity());
    }

    public <T> Collection<T> collection(String collection, Class<T> type) {
        return collection(collection, new JacksonCodec<>(type, context.json()));
    }

    public <T> Collection<T> collection(String collection, DocumentCodec<T> codec) {
        return new Collection<>(new CollectionOps<>(context, context.channel(), name, collection, codec));
    }

    public Document stats() {
        final var reply = context.channel().send(Operation.GET_DATABASE_STATS,
                context.encoder().databaseStats(Optional.of(name)));
        return ResponseDecoder.document(reply, "stats").orElseGet(Document::empty);
    }

    public void setOwners(List<String> owners) {
        context.channel().send(Operation.SET_DATABASE_OWNERS, context.encoder().setDatabaseOwners(name, owners));
    }

    public ScriptResult runScript(String script) {
        return runScript(script, Optional.empty());
    }

    public ScriptResult runScript(String script, Document args) {
        return runScript(script, Optional.of(args));
    }

    public Procedures procedures() {
        return new Procedures(context, name);
    }

    public Schedules schedules() {
        return new Schedules(context, name);
    }

    public List<TriggerInfo> listTriggers() {
        final var reply = context.channel().send(Operation.LIST_TRIGGERS,
                context.scripts().listTriggers(name, Optional.empty()));
        return ResponseDecoder.documents(reply, "triggers").stream().map(Descriptors::trigger).toList();
    }

    private ScriptResult runScript(String script, Optional<Document> args) {
        return ScriptResults.of(context.json(),
                context.channel().send(Operation.RUN_SCRIPT, context.scripts().runScript(name, script, args)));
    }
}

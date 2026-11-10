package io.github.christiancg.lwnrdb.client.scripting;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.ScriptResult;
import io.github.christiancg.lwnrdb.client.internal.ClientContext;
import io.github.christiancg.lwnrdb.client.internal.Descriptors;
import io.github.christiancg.lwnrdb.client.internal.ResponseDecoder;
import io.github.christiancg.lwnrdb.client.internal.ScriptResults;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import java.util.List;
import java.util.Optional;

public final class Procedures {
    private final ClientContext context;
    private final String database;

    public Procedures(ClientContext context, String database) {
        this.context = context;
        this.database = database;
    }

    public long save(ProcedureDefinition procedure) {
        return context.channel().send(Operation.SAVE_PROCEDURE, context.scripts().saveProcedure(database, procedure))
                .path("version").asLong(0);
    }

    public void delete(String name) {
        context.channel().send(Operation.DELETE_PROCEDURE, context.scripts().deleteProcedure(database, name));
    }

    public List<ProcedureInfo> list() {
        return list(Optional.empty());
    }

    public List<ProcedureInfo> list(boolean includeSource) {
        return list(Optional.of(includeSource));
    }

    public ScriptResult call(String name) {
        return call(name, Optional.empty());
    }

    public ScriptResult call(String name, Document args) {
        return call(name, Optional.of(args));
    }

    private List<ProcedureInfo> list(Optional<Boolean> includeSource) {
        final var reply = context.channel().send(Operation.LIST_PROCEDURES,
                context.scripts().listProcedures(database, includeSource));
        return ResponseDecoder.documents(reply, "procedures").stream().map(Descriptors::procedure).toList();
    }

    private ScriptResult call(String name, Optional<Document> args) {
        return ScriptResults.of(context.json(), context.channel().send(Operation.CALL_PROCEDURE,
                context.scripts().callProcedure(database, name, args)));
    }
}

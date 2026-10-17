package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.simplejs.ModuleReferences;
import org.techhouse.simplejs.SimpleJs;

public final class ProcedureBindingGuard {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final ModuleReferences NO_REFERENCES = new ModuleReferences(Set.of(), false);

    private ProcedureBindingGuard() {
    }

    public static OperationResponse refusal(OperationType type, String dbName, String procedureName, String actingUser)
            throws IOException {
        if (exempt(dbName, actingUser)) {
            return null;
        }
        final var memo = new HashMap<String, ModuleReferences>();
        for (final var boundProcedure : foreignBoundProcedures(dbName, actingUser)) {
            if (reaches(dbName, boundProcedure, procedureName, memo)) {
                return new OperationResponse(type, "Procedure '" + procedureName
                        + "' is bound by a trigger or schedule that runs as another user; only that user, a database"
                        + " owner or an admin may change it", ErrorCode.PROCEDURE_BOUND_BY_OTHER_DEFINER);
            }
        }
        return null;
    }

    static OperationResponse foreignDefinitionRefusal(OperationType type, String dbName, String definer,
            String actingUser) {
        if (definer == null || definer.equals(actingUser) || exempt(dbName, actingUser)) {
            return null;
        }
        return new OperationResponse(type, ErrorCode.DEFINITION_OWNED_BY_OTHER_DEFINER);
    }

    private static boolean exempt(String dbName, String actingUser) {
        if (actingUser == null) {
            return false;
        }
        final var user = cache.getAdminUserEntry(actingUser);
        if (user != null && user.isAdmin()) {
            return true;
        }
        final var dbEntry = cache.getAdminDbEntry(dbName);
        return dbEntry != null && dbEntry.isOwner(actingUser);
    }

    private static Set<String> foreignBoundProcedures(String dbName, String actingUser) throws IOException {
        final var bindings = new LinkedHashSet<String>();
        for (final var trigger : TriggerOperationHelper.triggersIn(dbName)) {
            addIfForeign(bindings, trigger.getDefiner(), trigger.getProcedureName(), actingUser);
        }
        for (final var schedule : ScheduleOperationHelper.schedulesIn(dbName)) {
            addIfForeign(bindings, schedule.getDefiner(), schedule.getProcedureName(), actingUser);
        }
        return bindings;
    }

    private static void addIfForeign(Set<String> bindings, String definer, String procedureName, String actingUser) {
        if (actingUser == null || !actingUser.equals(definer)) {
            bindings.add(procedureName);
        }
    }

    private static boolean reaches(String dbName, String from, String target, Map<String, ModuleReferences> memo) {
        final var visited = new HashSet<String>();
        final var pending = new ArrayDeque<String>();
        pending.add(from);
        while (!pending.isEmpty()) {
            final var name = pending.poll();
            if (name.equals(target)) {
                return true;
            }
            if (!visited.add(name)) {
                continue;
            }
            final var references = memo.computeIfAbsent(name, key -> referencesOf(dbName, key));
            if (references.opaque()) {
                return true;
            }
            pending.addAll(references.procedureNames());
        }
        return false;
    }

    private static ModuleReferences referencesOf(String dbName, String name) {
        final var definition = cache.getProcedure(dbName, name);
        if (definition == null || definition.getSource() == null) {
            return NO_REFERENCES;
        }
        final var compiled = compiledProcedures.get(dbName, name, definition.getVersion(), definition.getSource());
        return simpleJs.moduleReferences(compiled);
    }
}

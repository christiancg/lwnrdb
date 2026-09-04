package org.techhouse.ops;

import org.techhouse.ioc.IocContainer;

/**
 * The number of script runs executing on this node right now - RUN_SCRIPT, CALL_PROCEDURE and trigger
 * dispatch alike, since they all consume the same interpreter CPU. Gossiped with the heartbeat as the
 * placement signal {@code cluster/ScriptPlacement} samples, so it is a live hint rather than an
 * accounting figure.
 *
 * <p>
 * The count is {@link ScriptRunRegistry}'s size rather than a counter of its own: the number placement acts
 * on and the runs LIST_SCRIPTS reports are then the same set by construction.
 */
public class ScriptLoad {
    private static final ScriptRunRegistry registry = IocContainer.get(ScriptRunRegistry.class);

    public int current() {
        return registry.size();
    }
}

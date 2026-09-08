package org.techhouse.simplejs;

import org.techhouse.simplejs.nodes.Program;

public record CompiledScript(Program program, String source, boolean strictScriptGoal, String sourceHash) {
}

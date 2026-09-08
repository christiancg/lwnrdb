package org.techhouse.simplejs;

import org.techhouse.simplejs.nodes.Program;

public final class CompiledScript {
    private final Program program;
    private final String source;
    private final boolean strictScriptGoal;
    private final String sourceHash;

    public CompiledScript(Program program, String source, boolean strictScriptGoal, String sourceHash) {
        this.program = program;
        this.source = source;
        this.strictScriptGoal = strictScriptGoal;
        this.sourceHash = sourceHash;
    }

    public Program program() {
        return program;
    }

    public String source() {
        return source;
    }

    public boolean strictScriptGoal() {
        return strictScriptGoal;
    }

    public String sourceHash() {
        return sourceHash;
    }
}

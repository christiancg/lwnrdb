package org.techhouse.simplejs;

import org.techhouse.simplejs.nodes.Program;

/**
 * A parsed program, safe to keep and re-run. Nothing in the AST is written after parsing (the parser is the
 * only writer of {@code JsNode.sourceText}, and a regex literal holds its pattern text rather than a
 * compiled {@code JsRegExp} with a mutable {@code lastIndex}), and every piece of runtime state lives in the
 * per-run {@code Interpreter}/{@code Environment}/{@code Intrinsics}. That is what lets one instance back a
 * cache shared by every call of a stored procedure.
 *
 * <p>
 * The source is retained because the parse goal is a property of the parse: a run whose host asks for a
 * different goal has to parse again rather than run a program whose early errors were decided differently.
 */
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

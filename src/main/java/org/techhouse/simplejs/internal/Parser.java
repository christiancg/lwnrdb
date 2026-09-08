package org.techhouse.simplejs.internal;

import java.util.List;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.internal.parser.ParserProductions;
import org.techhouse.simplejs.nodes.Program;

public final class Parser {
    private Parser() {
    }

    public static Program parse(List<JsBaseElement> tokens) {
        return new ParserProductions(null, tokens, null, null, false).parseProgram();
    }

    public static Program parse(Lexer.LexResult lexed) {
        return parse(lexed, false);
    }

    public static Program parse(Lexer.LexResult lexed, boolean strictScriptGoal) {
        return new ParserProductions(lexed.source(), lexed.tokens(), lexed.positions(), lexed.newlineBefore(),
                strictScriptGoal).parseProgram();
    }

}

package org.techhouse.simplejs.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsBigInt;
import org.techhouse.simplejs.elements.JsBoolean;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsPrivateIdentifier;
import org.techhouse.simplejs.elements.JsRegex;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.parser.DeclarationScope;
import org.techhouse.simplejs.internal.parser.ParserTables;
import org.techhouse.simplejs.internal.parser.PatternConverter;
import org.techhouse.simplejs.internal.parser.PrivateScope;
import org.techhouse.simplejs.internal.parser.TokenStream;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.AwaitExpression;
import org.techhouse.simplejs.nodes.BigIntLiteral;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.BooleanLiteral;
import org.techhouse.simplejs.nodes.BreakStatement;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.CatchClause;
import org.techhouse.simplejs.nodes.ClassBody;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ClassExpression;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.ContinueStatement;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.EmptyStatement;
import org.techhouse.simplejs.nodes.ExportAllDeclaration;
import org.techhouse.simplejs.nodes.ExportDefaultDeclaration;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.ExportSpecifier;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.FieldDefinition;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.ImportAttribute;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.nodes.ImportDefaultSpecifier;
import org.techhouse.simplejs.nodes.ImportExpression;
import org.techhouse.simplejs.nodes.ImportNamespaceSpecifier;
import org.techhouse.simplejs.nodes.ImportSpecifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.MetaProperty;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.NullLiteral;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RegexLiteral;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.SequenceExpression;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StaticBlock;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.SwitchCase;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.TaggedTemplateExpression;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.ThisExpression;
import org.techhouse.simplejs.nodes.ThrowStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UndefinedLiteral;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.VariableDeclarator;
import org.techhouse.simplejs.nodes.WhileStatement;
import org.techhouse.simplejs.nodes.YieldExpression;

public final class Parser {
    private Parser() {
    }

    public static Program parse(List<JsBaseElement> tokens) {
        return new State(tokens, null, null, false).parseProgram();
    }

    public static Program parse(Lexer.LexResult lexed) {
        return parse(lexed, false);
    }

    // strictScriptGoal raises the early errors the ECMAScript Script goal imposes and the host
    // contract deliberately relaxes: a top-level `return`, `new.target` or `super` outside function
    // code, `import`/`export`, `import.meta`, and a top-level `using` declaration.
    public static Program parse(Lexer.LexResult lexed, boolean strictScriptGoal) {
        return new State(lexed.tokens(), lexed.positions(), lexed.newlineBefore(), strictScriptGoal).parseProgram();
    }

    // The recursive-descent walk is inherently stateful (a moving cursor over the token stream),
    // so the grammar productions live in this nested type. The cursor and token-level primitives
    // are inherited from TokenStream; the shared precedence tables live in ParserTables and the
    // cover-grammar reinterpretation in PatternConverter.
    private record FunctionParts(List<JsNode> params, BlockStatement body) {
    }

    private static final class State extends TokenStream {
        // The lexer tokenises these as keywords, but the grammar never reserves them: they are
        // IdentifierReferences and BindingIdentifiers wherever a member key or declaration expects one.
        private static final Set<String> CONTEXTUAL_KEYWORDS = Set.of("of", "async");

        // Keywords that can begin an expression, used to tell `await x` (the operator) apart from
        // `await` the identifier by a single-token lookahead.
        private static final Set<String> EXPRESSION_KEYWORDS = Set.of("this", "super", "new", "function", "class",
                "typeof", "void", "delete", "await", "yield", "async", "import", "of");

        private final PatternConverter patterns = new PatternConverter(this);
        // Parenthesised expressions, by identity: the AST keeps no marker for them, but the
        // ?? / && / || chaining early error only fires on an *unparenthesised* operand.
        private final Set<Expression> parenthesised = Collections.newSetFromMap(new IdentityHashMap<>());
        // Object literals carrying two `__proto__: v` properties, legal only as a destructuring target.
        private final Set<Expression> duplicateProto = Collections.newSetFromMap(new IdentityHashMap<>());
        private DeclarationScope scope = new DeclarationScope(null, true);
        private PrivateScope privateScope;
        private final Map<String, Boolean> labels = new HashMap<>();
        private int iterationDepth;
        private int switchDepth;
        private boolean inStaticBlock;
        private boolean inGenerator;
        private boolean inAsync = true;
        // The grammar's [Await] parameter: where `await` may not be an identifier. It is set across an
        // async function's parameters *and* body and across a class static block, and reset by any
        // function boundary including an arrow's body (ConciseBody carries no [Await]) - which is why
        // it is tracked separately from inAsync, the flag that allows an AwaitExpression.
        private boolean awaitReserved;
        private boolean superCallAllowed;
        // A SuperProperty is only in the grammar inside a method, an accessor, a class field
        // initializer or a static block - an ordinary function body or the script's top level is an
        // early error, whatever encloses them.
        private boolean superPropertyAllowed;
        private boolean classHasHeritage;
        private final boolean strictScriptGoal;
        // Any function-like body, an arrow's included: where a `return` is allowed.
        private boolean inFunctionBody;
        // Function or class-member code only. An arrow inherits it, because the Script goal's
        // Contains looks through an arrow for `new.target` and `super` but not through a function.
        private boolean inNonArrowFunction;

        private State(List<JsBaseElement> tokens, List<SourcePosition> positions, List<Boolean> newlineBefore,
                boolean strictScriptGoal) {
            super(tokens, positions, newlineBefore);
            this.strictScriptGoal = strictScriptGoal;
        }

        private <T> T inScope(Supplier<T> production) {
            scope = new DeclarationScope(scope, false);
            try {
                return production.get();
            } finally {
                scope = scope.getParent();
            }
        }

        // A function's parameters share one scope with the top level of its body, so
        // `function f(a) { let a }` is rejected while `function f(a) { { let a } }` is not.
        private <T> T inFunctionScope(List<JsNode> params, Supplier<T> production) {
            scope = new DeclarationScope(scope, true);
            try {
                for (final var param : params) {
                    declareBoundNames(param, false);
                }
                return production.get();
            } finally {
                scope = scope.getParent();
            }
        }

        // An arrow keeps the enclosing static block's `arguments` restriction; a real function
        // introduces its own `arguments` binding and so lifts it across both params and body.
        private FunctionParts parseFunctionParts(boolean generator, boolean async) {
            return parseFunctionParts(generator, async, false, false);
        }

        private FunctionParts parseFunctionParts(boolean generator, boolean async, boolean superCall, boolean method) {
            final var wasSuperPropertyAllowed = superPropertyAllowed;
            superPropertyAllowed = method;
            try {
                return parseFunctionPartsInContext(generator, async, superCall);
            } finally {
                superPropertyAllowed = wasSuperPropertyAllowed;
            }
        }

        private FunctionParts parseFunctionPartsInContext(boolean generator, boolean async, boolean superCall) {
            final var wasInStaticBlock = inStaticBlock;
            final var wasInGenerator = inGenerator;
            final var wasInAsync = inAsync;
            final var wasSuperCallAllowed = superCallAllowed;
            final var wasInFunctionBody = inFunctionBody;
            final var wasInNonArrowFunction = inNonArrowFunction;
            final var wasAwaitReserved = awaitReserved;
            inStaticBlock = false;
            superCallAllowed = superCall;
            inFunctionBody = true;
            inNonArrowFunction = true;
            awaitReserved = async;
            try {
                final var params = outsideGenerator(this::parseParams);
                setFunctionKind(generator, async);
                final var body = inBreakableBoundary(() -> inFunctionScope(params, this::parseBlockBody));
                checkUseStrictWithSimpleParams(params, body);
                return new FunctionParts(params, body);
            } finally {
                inStaticBlock = wasInStaticBlock;
                inGenerator = wasInGenerator;
                inAsync = wasInAsync;
                superCallAllowed = wasSuperCallAllowed;
                inFunctionBody = wasInFunctionBody;
                inNonArrowFunction = wasInNonArrowFunction;
                awaitReserved = wasAwaitReserved;
            }
        }

        // A YieldExpression exists only in a generator body: its parameters, a nested arrow or
        // ordinary function, and a class field initializer are all outside it, and in always-strict
        // code `yield` cannot fall back to being an identifier.
        private <T> T outsideGenerator(Supplier<T> production) {
            return inFunctionKind(false, production);
        }

        private <T> T inFunctionKind(boolean async, Supplier<T> production) {
            final var wasInGenerator = inGenerator;
            final var wasInAsync = inAsync;
            setFunctionKind(false, async);
            try {
                return production.get();
            } finally {
                setFunctionKind(wasInGenerator, wasInAsync);
            }
        }

        private void setFunctionKind(boolean generator, boolean async) {
            inGenerator = generator;
            inAsync = async;
        }

        // It is a Syntax Error if a function body's directive prologue contains "use strict" while
        // its parameter list is not simple (destructuring, default, or rest parameters).
        private void checkUseStrictWithSimpleParams(List<JsNode> params, BlockStatement body) {
            if (isSimpleParameterList(params) || !containsUseStrictDirective(body)) {
                return;
            }
            throw new SyntaxErrorException("Illegal 'use strict' directive in function with non-simple parameter list");
        }

        private static boolean isSimpleParameterList(List<JsNode> params) {
            for (final var param : params) {
                if (!(param instanceof Identifier)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean containsUseStrictDirective(BlockStatement body) {
            for (final var statement : body.getBody()) {
                if (!(statement instanceof ExpressionStatement stmt)
                        || !(stmt.getExpression() instanceof StringLiteral str)) {
                    break;
                }
                if ("use strict".equals(str.getValue())) {
                    return true;
                }
            }
            return false;
        }

        private void declareBoundNames(JsNode target, boolean isLexical) {
            final var names = new ArrayList<String>();
            collectNames(target, names);
            for (final var name : names) {
                if (isLexical) {
                    scope.declareLexical(name);
                } else {
                    scope.declareVar(name);
                }
            }
        }

        private static void collectNames(JsNode node, List<String> names) {
            switch (node) {
                case null -> {
                }
                case Identifier id -> names.add(id.getName());
                case RestElement rest -> collectNames(rest.getArgument(), names);
                case AssignmentPattern assignment -> collectNames(assignment.getLeft(), names);
                case ArrayPattern array -> array.getElements().forEach(element -> collectNames(element, names));
                case ObjectPattern object -> object.getProperties().forEach(prop -> collectNames(prop, names));
                case Property prop -> collectNames(prop.getValue(), names);
                default -> {
                }
            }
        }

        private Program parseProgram() {
            final var body = new ArrayList<Statement>();
            while (!atEnd()) {
                body.add(parseStatement());
            }
            return new Program(body);
        }

        private Statement parseStatement() {
            if (isSeparator('{')) {
                return parseBlock();
            }
            if (matchSeparator(';')) {
                return new EmptyStatement();
            }
            if (current().getType() == JsType.KEYWORD) {
                switch (((JsKeyword) current()).getValue()) {
                    case "var", "let", "const" -> {
                        return parseVariableDeclaration();
                    }
                    case "if" -> {
                        return parseIf();
                    }
                    case "while" -> {
                        return parseWhile();
                    }
                    case "do" -> {
                        return parseDoWhile();
                    }
                    case "for" -> {
                        return parseFor();
                    }
                    case "try" -> {
                        return parseTry();
                    }
                    case "throw" -> {
                        return parseThrow();
                    }
                    case "switch" -> {
                        return parseSwitch();
                    }
                    case "return" -> {
                        return parseReturn();
                    }
                    case "break" -> {
                        return parseBreak();
                    }
                    case "continue" -> {
                        return parseContinue();
                    }
                    case "function" -> {
                        return parseFunctionDeclaration(false);
                    }
                    case "async" -> {
                        // `async [no LineTerminator here] function`: a line break between the two
                        // demotes `async` to a plain (undeclared, in always-strict code) identifier
                        // reference, followed by a separate ordinary function declaration.
                        if (!newlineBeforePeek() && peek().getType() == JsType.KEYWORD
                                && "function".equals(((JsKeyword) peek()).getValue())) {
                            advance();
                            return parseFunctionDeclaration(true);
                        }
                    }
                    case "class" -> {
                        return parseClassDeclaration();
                    }
                    case "import" -> {
                        if (isDynamicImportOrMeta()) {
                            return parseExpressionStatement();
                        }
                        return parseImportDeclaration();
                    }
                    case "export" -> {
                        return parseExportDeclaration();
                    }
                    default -> {
                    }
                }
            }
            if (isContextualKeyword("with") && peek().getType() == JsType.SEPARATOR
                    && ((JsSeparator) peek()).getValue() == '(') {
                throw new SyntaxErrorException("Strict mode code may not include a with statement");
            }
            // `debugger` is a reserved word the lexer keeps as an identifier token, so the statement is
            // recognised here and every other position rejects the word.
            if (isContextualKeyword("debugger")) {
                advance();
                consumeSemicolon();
                return new EmptyStatement();
            }
            if (isUsingDeclarationStart()) {
                return parseUsingDeclaration();
            }
            if (isAwaitUsingDeclarationStart()) {
                return parseAwaitUsingDeclaration();
            }
            // A LabelIdentifier is exactly as permissive as an IdentifierReference: `await`/`async`/`of`
            // written unescaped lex as KEYWORD tokens, not IDENTIFIER, but are legal labels wherever
            // they are legal identifiers (`keywordIsIdentifier` already carries that distinction).
            if ((current().getType() == JsType.IDENTIFIER || keywordIsIdentifier())
                    && peek().getType() == JsType.OPERATOR && ":".equals(((JsOperator) peek()).getValue())) {
                return parseLabeledStatement();
            }
            return parseExpressionStatement();
        }

        // `using x = e` — contextual: a declaration only when `using` is directly followed by a binding
        // identifier (so `using`, `using.foo()`, `using = 1`, `let using = 1` still parse as expressions).
        // `using of` never begins a *for-of* declaration: that head carries its own lookahead
        // restriction so `for (using of of x)` reads `using` as the loop's own target. Outside that
        // ambiguity - including a classic `for (using of = e;;)` or a plain `using of = e;` statement -
        // `of` is an ordinary bound name, told apart from the for-of case by the `=` that follows it.
        private boolean isUsingDeclarationStart() {
            return isContextualKeyword("using") && identifierLikeAt(1) && (isNotKeywordAt(1)
                    || peekAt(2).getType() == JsType.OPERATOR && "=".equals(((JsOperator) peekAt(2)).getValue()));
        }

        // `await using x = e` — three-token lookahead; otherwise `await` stays an AwaitExpression.
        private boolean isAwaitUsingDeclarationStart() {
            return isKeyword("await") && peek().getType() == JsType.IDENTIFIER
                    && "using".equals(((JsIdentifier) peek()).getValue()) && identifierLikeAt(2);
        }

        private boolean identifierLikeAt(int offset) {
            final var t = peekAt(offset);
            if (t.getType() == JsType.IDENTIFIER) {
                return true;
            }
            if (t.getType() != JsType.KEYWORD) {
                return false;
            }
            final var kw = ((JsKeyword) t).getValue();
            return CONTEXTUAL_KEYWORDS.contains(kw) || "await".equals(kw) && !awaitIsReserved();
        }

        private boolean isNotKeywordAt(int offset) {
            final var t = peekAt(offset);
            return t.getType() != JsType.KEYWORD || !((JsKeyword) t).getValue().equals("of");
        }

        private VariableDeclaration parseUsingDeclaration() {
            rejectTopLevelUsing("using");
            expectContextualKeyword("using");
            return new VariableDeclaration("using", parseUsingDeclarators());
        }

        private VariableDeclaration parseAwaitUsingDeclaration() {
            rejectTopLevelUsing("await using");
            expectKeyword("await");
            expectContextualKeyword("using");
            return new VariableDeclaration("await using", parseUsingDeclarators());
        }

        // In the Script goal a using declaration has to sit inside a block, a loop, a function body,
        // a class body or a static block - never directly in the script's own statement list. The
        // root declaration scope is the only one with no parent, so it identifies that position.
        private void rejectTopLevelUsing(String kind) {
            if (strictScriptGoal && scope.getParent() == null) {
                throw new SyntaxErrorException(
                        "A " + kind + " declaration is not allowed at the top level of a script");
            }
        }

        private void rejectOutsideFunctionCode(String construct) {
            if (strictScriptGoal && !inNonArrowFunction) {
                throw new SyntaxErrorException("'" + construct + "' is only allowed in function code");
            }
        }

        private void rejectInScriptGoal(String construct) {
            if (strictScriptGoal) {
                throw new SyntaxErrorException(construct + " may only appear in a module");
            }
        }

        // A using declaration binds only plain identifiers and requires an initializer on each.
        private List<VariableDeclarator> parseUsingDeclarators() {
            final var declarations = new ArrayList<VariableDeclarator>();
            do {
                final var id = parseBindingIdentifier();
                expectOperator("=");
                declareBoundNames(id, true);
                declarations.add(new VariableDeclarator(id, parseAssignment()));
            } while (matchSeparator(','));
            consumeSemicolon();
            return declarations;
        }

        private LabeledStatement parseLabeledStatement() {
            rejectEscapedReserved();
            final var label = parseIdentifier();
            expectOperator(":");
            if (labels.containsKey(label.getName())) {
                throw new SyntaxErrorException("Label '" + label.getName() + "' has already been declared");
            }
            labels.put(label.getName(), labelsIterationStatement());
            try {
                return new LabeledStatement(label, parseNestedStatement());
            } finally {
                labels.remove(label.getName());
            }
        }

        // `continue label` only reaches a label that ultimately labels an iteration statement, so a
        // chain of labels is resolved down to the statement it finally introduces.
        private boolean labelsIterationStatement() {
            var offset = 0;
            while (peekAt(offset).getType() == JsType.IDENTIFIER && peekAt(offset + 1).getType() == JsType.OPERATOR
                    && ":".equals(((JsOperator) peekAt(offset + 1)).getValue())) {
                offset += 2;
            }
            final var statement = peekAt(offset);
            return statement.getType() == JsType.KEYWORD
                    && ParserTables.ITERATION_KEYWORDS.contains(((JsKeyword) statement).getValue());
        }

        // break and continue never cross a function boundary, so a nested function body starts with
        // an empty label set and no enclosing iteration or switch statement.
        // the depth resets are read by the production lambda, which PMD's dataflow cannot see
        @SuppressWarnings("PMD.UnusedAssignment")
        private <T> T inBreakableBoundary(Supplier<T> production) {
            final var enclosingLabels = new HashMap<>(labels);
            final var enclosingIterations = iterationDepth;
            final var enclosingSwitches = switchDepth;
            labels.clear();
            iterationDepth = 0;
            switchDepth = 0;
            try {
                return production.get();
            } finally {
                labels.clear();
                labels.putAll(enclosingLabels);
                iterationDepth = enclosingIterations;
                switchDepth = enclosingSwitches;
            }
        }

        private Statement parseLoopBody() {
            iterationDepth++;
            try {
                return parseNestedStatement();
            } finally {
                iterationDepth--;
            }
        }

        // The body of if/else, a loop or a label is a Statement, never a Declaration: `if (x) let y;`
        // and `while (x) function f() {}` are early errors, and in strict code so is a labelled
        // function declaration.
        private Statement parseNestedStatement() {
            if (isDeclarationStart()) {
                throw new SyntaxErrorException("Declaration is not allowed in statement position");
            }
            return parseStatement();
        }

        private boolean isDeclarationStart() {
            if (isKeyword("function") || isKeyword("class") || isKeyword("let") || isKeyword("const")) {
                return true;
            }
            if (isKeyword("async") && !newlineBeforePeek() && peek().getType() == JsType.KEYWORD
                    && "function".equals(((JsKeyword) peek()).getValue())) {
                return true;
            }
            return isUsingDeclarationStart() || isAwaitUsingDeclarationStart();
        }

        private BlockStatement parseBlock() {
            return inScope(this::parseBlockBody);
        }

        private BlockStatement parseBlockBody() {
            expectSeparator('{');
            final var body = new ArrayList<Statement>();
            while (!isSeparator('}') && !atEnd()) {
                body.add(parseStatement());
            }
            expectSeparator('}');
            return new BlockStatement(body);
        }

        private VariableDeclaration parseVariableDeclaration() {
            final var kind = ((JsKeyword) advance()).getValue();
            final var declarations = new ArrayList<VariableDeclarator>();
            do {
                final var id = parseBindingTarget();
                Expression init = null;
                if (matchOperator("=")) {
                    init = parseAssignment();
                }
                if (init == null && "const".equals(kind)) {
                    throw new SyntaxErrorException("Missing initializer in const declaration");
                }
                declareBoundNames(id, !"var".equals(kind));
                declarations.add(new VariableDeclarator(id, init));
            } while (matchSeparator(','));
            consumeSemicolon();
            return new VariableDeclaration(kind, declarations);
        }

        private IfStatement parseIf() {
            expectKeyword("if");
            expectSeparator('(');
            final var test = parseExpression();
            expectSeparator(')');
            final var consequent = parseNestedStatement();
            Statement alternate = null;
            if (matchKeyword("else")) {
                alternate = parseNestedStatement();
            }
            return new IfStatement(test, consequent, alternate);
        }

        private WhileStatement parseWhile() {
            expectKeyword("while");
            expectSeparator('(');
            final var test = parseExpression();
            expectSeparator(')');
            final var body = parseLoopBody();
            return new WhileStatement(test, body);
        }

        private DoWhileStatement parseDoWhile() {
            expectKeyword("do");
            final var body = parseLoopBody();
            expectKeyword("while");
            expectSeparator('(');
            final var test = parseExpression();
            expectSeparator(')');
            consumeDoWhileSemicolon();
            return new DoWhileStatement(body, test);
        }

        private Statement parseFor() {
            return inScope(this::parseForRest);
        }

        private Statement parseForRest() {
            expectKeyword("for");
            final var isAwait = isKeyword("await");
            if (isAwait) {
                advance();
            }
            expectSeparator('(');
            if (isSeparator(';')) {
                if (isAwait) {
                    throw error();
                }
                return parseClassicForRest(null);
            }
            // ForInOfStatement's head carries `[lookahead ∉ { let, async of }]`, so a bare `async`
            // followed by `of` never begins a for-of head even though it is a valid reference elsewhere.
            final var asyncHead = !isAwait && isKeyword("async");
            final var init = parseForHeaderLeft();
            if (isKeyword("in") || isKeyword("of")) {
                if (asyncHead && isKeyword("of") && init instanceof Identifier id && "async".equals(id.getName())) {
                    throw new SyntaxErrorException("The left-hand side of a for-of loop may not be the token 'async'");
                }
                return parseForInOf(init, isAwait);
            }
            if (isAwait) {
                throw error();
            }
            return parseClassicForRest(init);
        }

        private JsNode parseForHeaderLeft() {
            if (isKeyword("var") || isKeyword("let") || isKeyword("const")) {
                return parseForVariableDeclaration();
            }
            if (isUsingDeclarationStart()) {
                expectContextualKeyword("using");
                return parseForUsingDeclaration("using");
            }
            if (isAwaitUsingDeclarationStart()) {
                expectKeyword("await");
                expectContextualKeyword("using");
                return parseForUsingDeclaration("await using");
            }
            return withNoIn(this::parseExpression);
        }

        // A `for (using x of e)` head binds exactly one name with no initializer, while a classic
        // `for (using x = e; ;)` head is an ordinary declaration list, so which one this is only
        // becomes clear at the token following the bindings.
        private VariableDeclaration parseForUsingDeclaration(String kind) {
            final var declarations = new ArrayList<VariableDeclarator>();
            do {
                final var id = parseBindingIdentifier();
                Expression init = null;
                if (matchOperator("=")) {
                    init = withNoIn(this::parseAssignment);
                }
                declareBoundNames(id, true);
                declarations.add(new VariableDeclarator(id, init));
            } while (matchSeparator(','));
            if (!isKeyword("of") && declarations.stream().anyMatch(declarator -> declarator.getInit() == null)) {
                throw new SyntaxErrorException("Missing initializer in " + kind + " declaration");
            }
            return new VariableDeclaration(kind, declarations);
        }

        private VariableDeclaration parseForVariableDeclaration() {
            final var kind = ((JsKeyword) advance()).getValue();
            return withNoIn(() -> {
                final var declarations = new ArrayList<VariableDeclarator>();
                do {
                    final var id = parseBindingTarget();
                    Expression init = null;
                    if (matchOperator("=")) {
                        init = parseAssignment();
                    }
                    declareBoundNames(id, !"var".equals(kind));
                    declarations.add(new VariableDeclarator(id, init));
                } while (matchSeparator(','));
                return new VariableDeclaration(kind, declarations);
            });
        }

        private Statement parseForInOf(JsNode left, boolean isAwait) {
            final var target = left instanceof ArrayExpression || left instanceof ObjectExpression
                    ? patterns.toAssignmentPattern((Expression) left)
                    : left;
            final var isOf = "of".equals(((JsKeyword) current()).getValue());
            patterns.validateForInOfTarget(target, isOf);
            if (isAwait && !isOf) {
                throw error();
            }
            advance();
            final var right = isOf ? parseAssignment() : parseExpression();
            expectSeparator(')');
            final var body = parseLoopBody();
            return isOf ? new ForOfStatement(target, right, body, isAwait) : new ForInStatement(target, right, body);
        }

        private ForStatement parseClassicForRest(JsNode init) {
            if (init instanceof VariableDeclaration declaration && "const".equals(declaration.getKind())) {
                for (final var declarator : declaration.getDeclarations()) {
                    if (declarator.getInit() == null) {
                        throw new SyntaxErrorException("Missing initializer in const declaration");
                    }
                }
            }
            expectSeparator(';');
            Expression test = null;
            if (!isSeparator(';')) {
                test = parseExpression();
            }
            expectSeparator(';');
            Expression update = null;
            if (!isSeparator(')')) {
                update = parseExpression();
            }
            expectSeparator(')');
            final var body = parseLoopBody();
            return new ForStatement(init, test, update, body);
        }

        private TryStatement parseTry() {
            expectKeyword("try");
            final var block = parseBlock();
            CatchClause handler = null;
            if (isKeyword("catch")) {
                handler = parseCatch();
            }
            BlockStatement finalizer = null;
            if (matchKeyword("finally")) {
                finalizer = parseBlock();
            }
            if (handler == null && finalizer == null) {
                throw error();
            }
            return new TryStatement(block, handler, finalizer);
        }

        // The catch parameter shares one scope with the block, so its bound names may not repeat, may
        // not be redeclared lexically in the block, and may not be shadowed by a function declaration
        // there. Declaring them in the block's own scope is what makes DeclarationScope catch all three.
        private CatchClause parseCatch() {
            expectKeyword("catch");
            return inScope(this::parseCatchRest);
        }

        private CatchClause parseCatchRest() {
            JsNode param = null;
            if (matchSeparator('(')) {
                param = parseBindingTarget();
                expectSeparator(')');
                final var names = new ArrayList<String>();
                collectNames(param, names);
                for (final var name : names) {
                    scope.declareCatchParam(name);
                }
            }
            return new CatchClause(param, parseBlockBody());
        }

        private ThrowStatement parseThrow() {
            expectKeyword("throw");
            if (newlineBeforeCurrent()) {
                throw error();
            }
            final var argument = parseExpression();
            consumeSemicolon();
            return new ThrowStatement(argument);
        }

        private SwitchStatement parseSwitch() {
            expectKeyword("switch");
            expectSeparator('(');
            final var discriminant = parseExpression();
            expectSeparator(')');
            expectSeparator('{');
            // Every case clause shares one lexical scope, so `case 0: let x; default: let x` clashes.
            switchDepth++;
            final List<SwitchCase> cases;
            try {
                cases = inScope(() -> {
                    final var parsed = new ArrayList<SwitchCase>();
                    var seenDefault = false;
                    while (!isSeparator('}') && !atEnd()) {
                        final var clause = parseSwitchCase();
                        if (clause.getTest() == null) {
                            if (seenDefault) {
                                throw new SyntaxErrorException("More than one default clause in switch statement");
                            }
                            seenDefault = true;
                        }
                        parsed.add(clause);
                    }
                    return parsed;
                });
            } finally {
                switchDepth--;
            }
            expectSeparator('}');
            return new SwitchStatement(discriminant, cases);
        }

        private SwitchCase parseSwitchCase() {
            Expression test = null;
            if (matchKeyword("case")) {
                test = parseExpression();
            } else {
                expectKeyword("default");
            }
            expectOperator(":");
            final var consequent = new ArrayList<Statement>();
            while (!isKeyword("case") && !isKeyword("default") && !isSeparator('}') && !atEnd()) {
                if (isUsingDeclarationStart() || isAwaitUsingDeclarationStart()) {
                    throw new SyntaxErrorException("using declaration is not allowed in a case or default clause");
                }
                consequent.add(parseStatement());
            }
            return new SwitchCase(test, consequent);
        }

        private ReturnStatement parseReturn() {
            // A class static block is function-like code but its body is a StatementList with no
            // [Return] parameter, so a `return` there is always an early error.
            if (inStaticBlock || strictScriptGoal && !inFunctionBody) {
                throw new SyntaxErrorException("Illegal return statement");
            }
            expectKeyword("return");
            Expression argument = null;
            if (!isSeparator(';') && !isSeparator('}') && !atEnd() && !newlineBeforeCurrent()) {
                argument = parseExpression();
            }
            consumeSemicolon();
            return new ReturnStatement(argument);
        }

        private BreakStatement parseBreak() {
            expectKeyword("break");
            final var label = current().getType() == JsType.IDENTIFIER && !newlineBeforeCurrent()
                    ? parseIdentifier()
                    : null;
            if (label == null) {
                if (iterationDepth == 0 && switchDepth == 0) {
                    throw new SyntaxErrorException("Illegal break statement");
                }
            } else if (!labels.containsKey(label.getName())) {
                throw undefinedLabel(label);
            }
            consumeSemicolon();
            return new BreakStatement(label);
        }

        private ContinueStatement parseContinue() {
            expectKeyword("continue");
            final var label = current().getType() == JsType.IDENTIFIER && !newlineBeforeCurrent()
                    ? parseIdentifier()
                    : null;
            if (label == null) {
                if (iterationDepth == 0) {
                    throw new SyntaxErrorException("Illegal continue statement");
                }
            } else if (!Boolean.TRUE.equals(labels.get(label.getName()))) {
                throw undefinedLabel(label);
            }
            consumeSemicolon();
            return new ContinueStatement(label);
        }

        private static SyntaxErrorException undefinedLabel(Identifier label) {
            return new SyntaxErrorException("Undefined label '" + label.getName() + "'");
        }

        private FunctionDeclaration parseFunctionDeclaration(boolean async) {
            expectKeyword("function");
            final var generator = matchOperator("*");
            final var name = parseBindingIdentifier();
            // A function declaration is var-scoped at a function boundary and lexical inside a block.
            declareBoundNames(name, !scope.isFunctionBoundary());
            final var parts = parseFunctionParts(generator, async);
            return new FunctionDeclaration(name, parts.params(), parts.body(), async, generator);
        }

        private ImportDeclaration parseImportDeclaration() {
            // `import` is a keyword, so import(...) dynamic imports and import.meta are not parsed here.
            rejectInScriptGoal("An import declaration");
            expectKeyword("import");
            if (current().getType() == JsType.STRING) {
                final var source = parseModuleSource();
                final var attributes = parseImportAttributes();
                consumeSemicolon();
                return new ImportDeclaration(List.of(), source, attributes);
            }
            final var specifiers = new ArrayList<JsNode>();
            if (matchOperator("*")) {
                specifiers.add(parseImportNamespaceSpecifier());
            } else if (isSeparator('{')) {
                parseNamedImportSpecifiers(specifiers);
            } else {
                specifiers.add(new ImportDefaultSpecifier(parseBindingIdentifier()));
                if (matchSeparator(',')) {
                    if (matchOperator("*")) {
                        specifiers.add(parseImportNamespaceSpecifier());
                    } else {
                        parseNamedImportSpecifiers(specifiers);
                    }
                }
            }
            expectContextualKeyword("from");
            final var source = parseModuleSource();
            final var attributes = parseImportAttributes();
            consumeSemicolon();
            return new ImportDeclaration(specifiers, source, attributes);
        }

        // ES2025 import attributes: an optional `with { key: "value", ... }` clause after the source.
        // Keys are identifier/keyword/string names; values must be string literals. `with` is contextual.
        private List<ImportAttribute> parseImportAttributes() {
            if (!matchContextualKeyword("with")) {
                return List.of();
            }
            expectSeparator('{');
            final var attributes = new ArrayList<ImportAttribute>();
            while (!isSeparator('}')) {
                final var key = parseModuleExportName();
                expectOperator(":");
                attributes.add(new ImportAttribute(key, parseModuleSource()));
                if (!isSeparator('}')) {
                    expectSeparator(',');
                }
            }
            expectSeparator('}');
            return attributes;
        }

        private ImportNamespaceSpecifier parseImportNamespaceSpecifier() {
            expectContextualKeyword("as");
            return new ImportNamespaceSpecifier(parseBindingIdentifier());
        }

        private void parseNamedImportSpecifiers(List<JsNode> specifiers) {
            expectSeparator('{');
            while (!isSeparator('}')) {
                final var imported = parseModuleExportName();
                final Identifier local;
                if (matchContextualKeyword("as")) {
                    local = parseBindingIdentifier();
                } else {
                    local = asBindingIdentifier(imported);
                    validateBindingName(local.getName());
                }
                specifiers.add(new ImportSpecifier(imported, local));
                if (!isSeparator('}')) {
                    expectSeparator(',');
                }
            }
            expectSeparator('}');
        }

        private Statement parseExportDeclaration() {
            rejectInScriptGoal("An export declaration");
            expectKeyword("export");
            if (matchKeyword("default")) {
                final var declaration = parseAssignment();
                consumeSemicolon();
                return new ExportDefaultDeclaration(declaration);
            }
            if (matchOperator("*")) {
                return parseExportAll();
            }
            if (isSeparator('{')) {
                return parseExportNamed();
            }
            return new ExportNamedDeclaration(parseExportedDeclaration(), List.of(), null, List.of());
        }

        private ExportAllDeclaration parseExportAll() {
            Identifier exported = null;
            if (matchContextualKeyword("as")) {
                exported = asBindingIdentifier(parseModuleExportName());
            }
            expectContextualKeyword("from");
            final var source = parseModuleSource();
            final var attributes = parseImportAttributes();
            consumeSemicolon();
            return new ExportAllDeclaration(exported, source, attributes);
        }

        private ExportNamedDeclaration parseExportNamed() {
            expectSeparator('{');
            final var specifiers = new ArrayList<ExportSpecifier>();
            while (!isSeparator('}')) {
                final var local = parseModuleExportName();
                final var exported = matchContextualKeyword("as") ? parseModuleExportName() : local;
                specifiers.add(new ExportSpecifier(local, exported));
                if (!isSeparator('}')) {
                    expectSeparator(',');
                }
            }
            expectSeparator('}');
            StringLiteral source = null;
            var attributes = List.<ImportAttribute>of();
            if (matchContextualKeyword("from")) {
                source = parseModuleSource();
                attributes = parseImportAttributes();
            }
            consumeSemicolon();
            return new ExportNamedDeclaration(null, specifiers, source, attributes);
        }

        private Statement parseExportedDeclaration() {
            if (isKeyword("var") || isKeyword("let") || isKeyword("const")) {
                return parseVariableDeclaration();
            }
            if (isKeyword("function")) {
                return parseFunctionDeclaration(false);
            }
            if (isKeyword("async") && peek().getType() == JsType.KEYWORD
                    && "function".equals(((JsKeyword) peek()).getValue())) {
                advance();
                return parseFunctionDeclaration(true);
            }
            if (isKeyword("class")) {
                return parseClassDeclaration();
            }
            throw error();
        }

        private StringLiteral parseModuleSource() {
            final var t = current();
            if (t.getType() != JsType.STRING) {
                throw error();
            }
            advance();
            return new StringLiteral(((JsString) t).getValue());
        }

        // A module name position accepts an identifier, a keyword-as-name (e.g. `default`), or a
        // string-literal name (`{ a as "x" }`).
        private Expression parseModuleExportName() {
            final var t = current();
            final Expression name = switch (t.getType()) {
                case IDENTIFIER -> new Identifier(((JsIdentifier) t).getValue());
                case KEYWORD -> new Identifier(((JsKeyword) t).getValue());
                case STRING -> new StringLiteral(((JsString) t).getValue());
                default -> throw error();
            };
            advance();
            return name;
        }

        private Identifier asBindingIdentifier(Expression name) {
            if (name instanceof Identifier id) {
                return id;
            }
            throw error();
        }

        private ExpressionStatement parseExpressionStatement() {
            final var expr = parseExpression();
            consumeSemicolon();
            return new ExpressionStatement(expr);
        }

        private List<JsNode> parseParams() {
            expectSeparator('(');
            final var params = new ArrayList<JsNode>();
            if (!isSeparator(')')) {
                do {
                    if (isSeparator(')')) {
                        break;
                    }
                    if (matchOperator("...")) {
                        params.add(new RestElement(parseBindingTarget()));
                        break;
                    }
                    params.add(parseBindingElement());
                } while (matchSeparator(','));
            }
            expectSeparator(')');
            checkNoDuplicateParams(params);
            return params;
        }

        // Strict mode forbids duplicate bound names across a parameter list, including inside patterns.
        private void checkNoDuplicateParams(List<JsNode> params) {
            final Set<String> names = new HashSet<>();
            for (final var param : params) {
                collectBoundNames(param, names);
            }
        }

        private void collectBoundNames(JsNode node, Set<String> names) {
            switch (node) {
                case null -> {
                }
                case Identifier id -> {
                    if (!names.add(id.getName())) {
                        throw new SyntaxErrorException("Duplicate parameter name not allowed in this context");
                    }
                }
                case RestElement rest -> collectBoundNames(rest.getArgument(), names);
                case AssignmentPattern assignment -> collectBoundNames(assignment.getLeft(), names);
                case ArrayPattern array -> array.getElements().forEach(element -> collectBoundNames(element, names));
                case ObjectPattern object -> object.getProperties().forEach(prop -> collectBoundNames(prop, names));
                case Property prop -> collectBoundNames(prop.getValue(), names);
                default -> {
                }
            }
        }

        private Identifier parseIdentifier() {
            final var t = current();
            if (t.getType() == JsType.IDENTIFIER) {
                advance();
                return new Identifier(((JsIdentifier) t).getValue());
            }
            if (keywordIsIdentifier()) {
                advance();
                return new Identifier(((JsKeyword) t).getValue());
            }
            throw error();
        }

        // `of`/`async` are never reserved; `await` is reserved only inside async function code, where
        // it always introduces an AwaitExpression. A binding position needs no lookahead - only a
        // reference position has to tell the operator apart from the identifier.
        private boolean keywordIsIdentifier() {
            final var t = current();
            if (t.getType() != JsType.KEYWORD) {
                return false;
            }
            final var kw = ((JsKeyword) t).getValue();
            return CONTEXTUAL_KEYWORDS.contains(kw) || "await".equals(kw) && !awaitIsReserved();
        }

        // At the top level of a script the host contract keeps top-level await, so `await` there is
        // the operator whenever an expression can follow it and the identifier otherwise.
        private boolean awaitIsReserved() {
            return awaitReserved;
        }

        // Outside async code an AwaitExpression is illegal anyway, so `await(x)` there is a call. Only
        // at the top level of a script, where the host contract keeps top-level await, do the two
        // readings compete and a single-token lookahead separates them.
        private boolean awaitIsIdentifierReference() {
            return !awaitIsReserved() && (!inAsync || !beginsExpression(peek()));
        }

        private static boolean beginsExpression(JsBaseElement t) {
            return switch (t.getType()) {
                case NUMBER, BIGINT, STRING, BOOLEAN, NULL, UNDEFINED, REGEX, TEMPLATE_STRING, IDENTIFIER,
                        PRIVATE_IDENTIFIER ->
                    true;
                case KEYWORD -> EXPRESSION_KEYWORDS.contains(((JsKeyword) t).getValue());
                case SEPARATOR -> {
                    final char c = ((JsSeparator) t).getValue();
                    yield c == '(' || c == '[' || c == '{';
                }
                case OPERATOR -> {
                    final var op = ((JsOperator) t).getValue();
                    yield ParserTables.PREFIX_UNARY_OPERATORS.contains(op) || "++".equals(op) || "--".equals(op);
                }
                default -> false;
            };
        }

        // A contextual keyword in a reference position, including the `x => …` arrow head it can form.
        private Expression parseContextualIdentifier(String name) {
            if (peek().getType() == JsType.OPERATOR && "=>".equals(((JsOperator) peek()).getValue())) {
                if (newlineBeforePeek()) {
                    throw error();
                }
                advance();
                advance();
                return parseArrowBody(List.of(new Identifier(name)), false);
            }
            advance();
            return new Identifier(name);
        }

        private JsNode parseBindingTarget() {
            if (isSeparator('[')) {
                return parseArrayPattern();
            }
            if (isSeparator('{')) {
                return parseObjectPattern();
            }
            return parseBindingIdentifier();
        }

        private Identifier parseBindingIdentifier() {
            rejectEscapedReserved();
            final var contextual = keywordIsIdentifier();
            final var id = parseIdentifier();
            if (!contextual) {
                validateBindingName(id.getName());
            }
            return id;
        }

        private static boolean isReservedWord(String name) {
            return Lexer.isReservedWord(name) || ParserTables.STRICT_RESERVED.contains(name)
                    || ParserTables.RESTRICTED_BINDINGS.contains(name);
        }

        // An escaped reserved word reaches the parser as an ordinary identifier token because an
        // escape never forms a keyword. Binding and label positions are LabelIdentifier/
        // BindingIdentifier, not IdentifierName, so it is a SyntaxError there - checked on the token
        // rather than the name so a contextual word like `with` is only rejected when escaped.
        private void rejectEscapedReserved() {
            if (current() instanceof JsIdentifier token && token.isEscaped() && isEscapeReserved(token.getValue())) {
                throw new SyntaxErrorException("Keyword must not contain escaped characters: " + token.getValue());
            }
        }

        // An escaped contextual keyword is only ever an identifier, so the escape is legal wherever
        // the plain word would also have been an identifier.
        private boolean isEscapeReserved(String word) {
            if (CONTEXTUAL_KEYWORDS.contains(word)) {
                return false;
            }
            if ("await".equals(word)) {
                return awaitIsReserved();
            }
            return Lexer.isReservedWord(word);
        }

        // `await` is a reserved word only where an AwaitExpression could appear (async code / top
        // level), not unconditionally like a genuine future-reserved word - so it needs the same
        // context-sensitive check `isEscapeReserved`/`keywordIsIdentifier` already use, rather than
        // the blanket `Lexer.isReservedWord` classification the other reserved words fall through to.
        private void validateBindingName(String name) {
            if ("await".equals(name)) {
                if (awaitIsReserved()) {
                    throw new SyntaxErrorException("'await' cannot be used as a binding identifier in strict mode");
                }
                return;
            }
            if (ParserTables.STRICT_RESERVED.contains(name) || ParserTables.RESTRICTED_BINDINGS.contains(name)
                    || Lexer.isReservedWord(name)) {
                throw new SyntaxErrorException("'" + name + "' cannot be used as a binding identifier in strict mode");
            }
        }

        // A binding target with an optional default (`x`, `x = 1`, `{a} = {}`), used for pattern
        // elements and function parameters.
        private JsNode parseBindingElement() {
            final var target = parseBindingTarget();
            if (matchOperator("=")) {
                return new AssignmentPattern(target, parseAssignment());
            }
            return target;
        }

        private ArrayPattern parseArrayPattern() {
            expectSeparator('[');
            final var elements = new ArrayList<JsNode>();
            while (!isSeparator(']')) {
                if (matchSeparator(',')) {
                    elements.add(null);
                    continue;
                }
                if (matchOperator("...")) {
                    elements.add(new RestElement(parseBindingTarget()));
                    break;
                }
                elements.add(parseBindingElement());
                if (!isSeparator(']')) {
                    expectSeparator(',');
                }
            }
            expectSeparator(']');
            return new ArrayPattern(elements);
        }

        private ObjectPattern parseObjectPattern() {
            expectSeparator('{');
            final var properties = new ArrayList<JsNode>();
            while (!isSeparator('}')) {
                if (matchOperator("...")) {
                    properties.add(new RestElement(parseBindingTarget()));
                    break;
                }
                properties.add(parseObjectPatternProperty());
                if (!isSeparator('}')) {
                    expectSeparator(',');
                }
            }
            expectSeparator('}');
            return new ObjectPattern(properties);
        }

        private Property parseObjectPatternProperty() {
            if (isSeparator('[')) {
                advance();
                final var key = withInAllowed(this::parseAssignment);
                expectSeparator(']');
                expectOperator(":");
                return new Property(key, parseBindingElement(), true, false);
            }
            final var t = current();
            // A shorthand binding's key doubles as an IdentifierReference, so a contextual keyword
            // (`async`/`of`, or `await` outside a reserved context) is as eligible as a genuine
            // identifier token - checked here, before advancing past it, the same way the object
            // *expression* shorthand at `parseProperty` already does. A contextual keyword is, by
            // definition, never subject to the reserved-word binding check below, matching
            // `parseBindingIdentifier`'s identical carve-out.
            final var contextualKeyword = t.getType() == JsType.KEYWORD && keywordIsIdentifier();
            final var shorthandIdentifier = t.getType() == JsType.IDENTIFIER || contextualKeyword;
            final Expression key = switch (t.getType()) {
                case STRING -> new StringLiteral(((JsString) t).getValue());
                case NUMBER -> new NumberLiteral(((JsNumber) t).getValue());
                // LiteralPropertyName : NumericLiteral is ToString(NumericValue); a BigInt's
                // NumericValue always has an exact decimal string form (no exponent notation).
                case BIGINT -> new StringLiteral(((JsBigInt) t).getValue().toString());
                default -> literalPropertyKey(t);
            };
            advance();
            if (matchOperator(":")) {
                return new Property(key, parseBindingElement(), false, false);
            }
            if (!shorthandIdentifier) {
                throw error();
            }
            assert key instanceof Identifier;
            if (!contextualKeyword) {
                validateBindingName(((Identifier) key).getName());
            }
            if (matchOperator("=")) {
                return new Property(key, new AssignmentPattern(key, parseAssignment()), false, true);
            }
            return new Property(key, key, false, true);
        }

        private Expression parseExpression() {
            final var first = parseAssignment();
            if (!isSeparator(',')) {
                return first;
            }
            final var expressions = new ArrayList<Expression>();
            expressions.add(first);
            while (matchSeparator(',')) {
                expressions.add(parseAssignment());
            }
            return new SequenceExpression(expressions);
        }

        private Expression parseAssignment() {
            if (isKeyword("yield")) {
                return parseYield();
            }
            final var left = parseConditional();
            if (current().getType() == JsType.OPERATOR) {
                final var op = ((JsOperator) current()).getValue();
                if (ParserTables.ASSIGNMENT_OPERATORS.contains(op)) {
                    final var target = patterns.resolveAssignmentTarget(left, op);
                    advance();
                    return new AssignmentExpression(op, target, parseAssignment());
                }
            }
            if (duplicateProto.contains(left)) {
                throw new SyntaxErrorException("Duplicate __proto__ fields are not allowed in object literals");
            }
            return left;
        }

        private Expression parseYield() {
            if (!inGenerator) {
                throw new SyntaxErrorException("yield is only valid inside a generator");
            }
            expectKeyword("yield");
            if (newlineBeforeCurrent()) {
                return new YieldExpression(null, false);
            }
            final var delegate = matchOperator("*");
            Expression argument = null;
            if (delegate) {
                argument = parseAssignment();
            } else if (hasYieldArgument()) {
                argument = parseAssignment();
            }
            return new YieldExpression(argument, delegate);
        }

        // A bare `yield` has no argument when the next token terminates the expression.
        private boolean hasYieldArgument() {
            final var t = current();
            if (t.getType() == JsType.EOF) {
                return false;
            }
            if (t.getType() == JsType.SEPARATOR) {
                final char c = ((JsSeparator) t).getValue();
                return c != ')' && c != ']' && c != '}' && c != ',' && c != ';';
            }
            return !(t.getType() == JsType.OPERATOR && ":".equals(((JsOperator) t).getValue()));
        }

        private Expression parseConditional() {
            final var test = parseBinary(0);
            if (matchOperator("?")) {
                // ConditionalExpression[In] : LogicalORExpression[?In] ? AssignmentExpression[+In] :
                // AssignmentExpression[?In] - the consequent always allows `in` even inside a noIn
                // production (a classic for-loop header), unlike the alternate, which keeps the
                // outer restriction.
                final var consequent = withInAllowed(this::parseAssignment);
                expectOperator(":");
                final var alternate = parseAssignment();
                return new ConditionalExpression(test, consequent, alternate);
            }
            return test;
        }

        private Expression parseBinary(int minPrec) {
            var left = parseUnary();
            var op = currentBinaryOperator();
            while (op != null && ParserTables.BINARY_PRECEDENCE.get(op) >= minPrec) {
                final int prec = ParserTables.BINARY_PRECEDENCE.get(op);
                advance();
                if ("in".equals(op) && left instanceof PrivateIdentifier) {
                    rejectPrivateInRhs();
                }
                final int nextMinPrec = "**".equals(op) ? prec : prec + 1;
                final var right = parseBinary(nextMinPrec);
                if (ParserTables.LOGICAL_OPERATORS.contains(op)) {
                    rejectCoalesceChain(op, left);
                    rejectCoalesceChain(op, right);
                    left = new LogicalExpression(op, left, right);
                } else {
                    left = new BinaryExpression(op, left, right);
                }
                op = currentBinaryOperator();
            }
            return left;
        }

        // CoalesceExpression's operands are not ShortCircuitExpressions, so `a ?? b || c` and
        // `a || b ?? c` are Syntax Errors unless the inner one is parenthesised.
        private void rejectCoalesceChain(String op, Expression operand) {
            if (!(operand instanceof LogicalExpression logical) || parenthesised.contains(operand)) {
                return;
            }
            final var inner = logical.getOperator();
            if ("??".equals(op) != "??".equals(inner)) {
                throw new SyntaxErrorException("Cannot chain '" + inner + "' with '" + op + "' without parentheses");
            }
        }

        // RelationalExpression : PrivateIdentifier in ShiftExpression restricts the right operand to
        // ShiftExpression grade - a production PrivateIdentifier itself is never part of (it is only
        // ever valid as this very production's own left operand), and an unparenthesised arrow
        // function is AssignmentExpression grade, likewise excluded. Both slip past the ordinary
        // precedence ladder because parsePrimary's fallback chain recognises a bare private name (when
        // immediately followed by `in`) and an arrow head at any precedence level, not just when
        // grammatically valid there - so the two malformed shapes are rejected explicitly, up front,
        // before attempting to parse a right operand that would otherwise wrongly succeed.
        private void rejectPrivateInRhs() {
            if (current().getType() == JsType.PRIVATE_IDENTIFIER) {
                throw new SyntaxErrorException("Unexpected private field in right-hand side of 'in'");
            }
            if (startsArrowFunction()) {
                throw new SyntaxErrorException("Unexpected arrow function in right-hand side of 'in'");
            }
        }

        // instanceof and in arrive as keyword tokens, not operators, so both sources feed the binary ladder.
        private String currentBinaryOperator() {
            final var t = current();
            if (t.getType() == JsType.OPERATOR) {
                final var op = ((JsOperator) t).getValue();
                return ParserTables.BINARY_PRECEDENCE.containsKey(op) ? op : null;
            }
            if (t.getType() == JsType.KEYWORD) {
                final var kw = ((JsKeyword) t).getValue();
                if ("in".equals(kw)) {
                    return Boolean.TRUE.equals(noInStack.peek()) ? null : "in";
                }
                return "instanceof".equals(kw) ? kw : null;
            }
            return null;
        }

        private Expression parseUnary() {
            final var t = current();
            if (t.getType() == JsType.OPERATOR) {
                final var op = ((JsOperator) t).getValue();
                if (ParserTables.PREFIX_UNARY_OPERATORS.contains(op)) {
                    advance();
                    return rejectExponentiationBase(new UnaryExpression(op, parseUnary(), true));
                }
                if ("++".equals(op) || "--".equals(op)) {
                    advance();
                    final var argument = parseUnary();
                    checkUpdateTarget(argument);
                    return new UpdateExpression(op, argument, true);
                }
            }
            if (t.getType() == JsType.KEYWORD) {
                final var kw = ((JsKeyword) t).getValue();
                if ("delete".equals(kw)) {
                    advance();
                    final var argument = parseUnary();
                    if (argument instanceof Identifier) {
                        throw new SyntaxErrorException("Delete of an unqualified identifier in strict mode");
                    }
                    if (argument instanceof MemberExpression member
                            && member.getProperty() instanceof PrivateIdentifier) {
                        throw new SyntaxErrorException("Private fields can not be deleted");
                    }
                    return rejectExponentiationBase(new UnaryExpression(kw, argument, true));
                }
                if ("typeof".equals(kw) || "void".equals(kw)) {
                    advance();
                    return rejectExponentiationBase(new UnaryExpression(kw, parseUnary(), true));
                }
                if ("await".equals(kw) && !awaitIsIdentifierReference()) {
                    advance();
                    if (!inAsync) {
                        throw new SyntaxErrorException("await is only valid inside an async function");
                    }
                    return rejectExponentiationBase(new AwaitExpression(parseUnary()));
                }
            }
            return parsePostfix();
        }

        // ExponentiationExpression takes an UpdateExpression as its base, so an unparenthesised
        // unary expression on the left of `**` is a Syntax Error.
        private Expression rejectExponentiationBase(Expression unary) {
            if (isOperator("**")) {
                throw new SyntaxErrorException("Unary operator used immediately before an exponentiation expression");
            }
            return unary;
        }

        private Expression parsePostfix() {
            final var expr = parseCallMember();
            if ((isOperator("++") || isOperator("--")) && !newlineBeforeCurrent()) {
                final var op = ((JsOperator) advance()).getValue();
                checkUpdateTarget(expr);
                return new UpdateExpression(op, expr, false);
            }
            return expr;
        }

        private static void checkUpdateTarget(Expression argument) {
            if (argument instanceof Identifier id && ParserTables.RESTRICTED_BINDINGS.contains(id.getName())) {
                throw new SyntaxErrorException("'" + id.getName() + "' cannot be updated in strict mode");
            }
            if (!isSimpleAssignmentTarget(argument)) {
                throw new SyntaxErrorException("Invalid left-hand side expression in an update expression");
            }
        }

        // AssignmentTargetType simple: a bare reference or a member access outside an optional chain.
        // Everything else - a call, `this`, `new.target`, a literal, a function - is invalid.
        private static boolean isSimpleAssignmentTarget(Expression expression) {
            if (expression instanceof Identifier) {
                return true;
            }
            return expression instanceof MemberExpression member && !member.isOptional()
                    && !isOptionalChain(member.getObject());
        }

        private static boolean isOptionalChain(Expression expression) {
            return switch (expression) {
                case MemberExpression member -> member.isOptional() || isOptionalChain(member.getObject());
                case CallExpression call -> call.isOptional() || isOptionalChain(call.getCallee());
                case null, default -> false;
            };
        }

        private Expression parseCallMember() {
            final var base = isKeyword("new") ? parseNew() : parsePrimary();
            return parseCallMemberTail(base);
        }

        private Expression parseCallMemberTail(Expression start) {
            var expr = start;
            var advancing = true;
            var optionalChain = false;
            while (advancing) {
                if (matchOperator(".")) {
                    expr = memberExpression(expr, parseMemberProperty(), false);
                } else if (matchOperator("?.")) {
                    optionalChain = true;
                    expr = parseOptionalTail(expr);
                } else if (isSeparator('[')) {
                    advance();
                    final var property = parseExpression();
                    expectSeparator(']');
                    expr = new MemberExpression(expr, property, true, false);
                } else if (isSeparator('(')) {
                    expr = new CallExpression(expr, parseArguments());
                } else if (current().getType() == JsType.TEMPLATE_STRING) {
                    // A tagged template is not part of an optional chain: `a?.b`x`` is an early error.
                    if (optionalChain) {
                        throw new SyntaxErrorException("Invalid tagged template on an optional chain");
                    }
                    final var template = parseTemplate((JsTemplateString) advance());
                    expr = new TaggedTemplateExpression(expr, template);
                } else {
                    advancing = false;
                }
            }
            return expr;
        }

        private Expression parseOptionalTail(Expression object) {
            if (isSeparator('(')) {
                return new CallExpression(object, parseArguments(), true);
            }
            if (isSeparator('[')) {
                advance();
                final var property = withInAllowed(this::parseExpression);
                expectSeparator(']');
                return new MemberExpression(object, property, true, true);
            }
            return memberExpression(object, parseMemberProperty(), true);
        }

        private Expression parseMemberProperty() {
            final var t = current();
            final var name = identifierName(t);
            if (name != null) {
                advance();
                return new Identifier(name);
            }
            if (t.getType() == JsType.PRIVATE_IDENTIFIER) {
                advance();
                return referencePrivateName(((JsPrivateIdentifier) t).getValue());
            }
            throw error();
        }

        // Every IdentifierName is a legal property name, including the literals `true`, `false`,
        // `null` and `undefined`, which the lexer turns into value tokens rather than keywords.
        private static String identifierName(JsBaseElement t) {
            return switch (t.getType()) {
                case IDENTIFIER -> ((JsIdentifier) t).getValue();
                case KEYWORD -> ((JsKeyword) t).getValue();
                case BOOLEAN -> String.valueOf(((JsBoolean) t).getValue());
                case NULL -> "null";
                case UNDEFINED -> "undefined";
                default -> null;
            };
        }

        private Identifier literalPropertyKey(JsBaseElement t) {
            final var name = identifierName(t);
            if (name == null) {
                throw error();
            }
            return new Identifier(name);
        }

        private PrivateIdentifier referencePrivateName(String name) {
            if (privateScope == null) {
                throw PrivateScope.undeclared(name);
            }
            privateScope.reference(name);
            return new PrivateIdentifier(name);
        }

        // MemberExpression : super . PrivateName is not in the grammar - a private name is never
        // reachable through the prototype chain.
        private MemberExpression memberExpression(Expression object, Expression property, boolean optional) {
            if (object instanceof SuperExpression && property instanceof PrivateIdentifier) {
                throw new SyntaxErrorException("Private fields can not be accessed on 'super'");
            }
            return new MemberExpression(object, property, false, optional);
        }

        private Expression parseNew() {
            expectKeyword("new");
            if (matchOperator(".")) {
                if (!isContextualKeyword("target")) {
                    throw error();
                }
                rejectOutsideFunctionCode("new.target");
                advance();
                return new MetaProperty("new", "target");
            }
            var callee = isKeyword("new") ? parseNew() : parsePrimary();
            callee = parseNewCalleeTail(callee);
            List<Expression> arguments = List.of();
            if (isSeparator('(')) {
                arguments = parseArguments();
            }
            return new NewExpression(callee, arguments);
        }

        private Expression parseNewCalleeTail(Expression start) {
            var expr = start;
            var advancing = true;
            while (advancing) {
                if (matchOperator(".")) {
                    expr = memberExpression(expr, parseMemberProperty(), false);
                } else if (isSeparator('[')) {
                    advance();
                    final var property = parseExpression();
                    expectSeparator(']');
                    expr = new MemberExpression(expr, property, true, false);
                } else if (current().getType() == JsType.TEMPLATE_STRING) {
                    final var template = parseTemplate((JsTemplateString) advance());
                    expr = new TaggedTemplateExpression(expr, template);
                } else {
                    advancing = false;
                }
            }
            return expr;
        }

        private List<Expression> parseArguments() {
            expectSeparator('(');
            final var arguments = new ArrayList<Expression>();
            if (!isSeparator(')')) {
                do {
                    if (isSeparator(')')) {
                        break;
                    }
                    arguments.add(parseSpreadableExpression());
                } while (matchSeparator(','));
            }
            expectSeparator(')');
            return arguments;
        }

        private Expression parseSpreadableExpression() {
            if (matchOperator("...")) {
                return new SpreadElement(withInAllowed(this::parseAssignment));
            }
            return withInAllowed(this::parseAssignment);
        }

        private Expression parsePrimary() {
            final var t = current();
            switch (t.getType()) {
                case NUMBER -> {
                    advance();
                    return new NumberLiteral(((JsNumber) t).getValue());
                }
                case BIGINT -> {
                    advance();
                    return new BigIntLiteral(((JsBigInt) t).getValue());
                }
                case STRING -> {
                    advance();
                    return new StringLiteral(((JsString) t).getValue());
                }
                case BOOLEAN -> {
                    advance();
                    return new BooleanLiteral(((JsBoolean) t).getValue());
                }
                case NULL -> {
                    advance();
                    return new NullLiteral();
                }
                case UNDEFINED -> {
                    advance();
                    return new UndefinedLiteral();
                }
                case REGEX -> {
                    advance();
                    final var pattern = ((JsRegex) t).getPattern();
                    final var flags = ((JsRegex) t).getFlags();
                    RegexTranslator.compile(pattern, flags);
                    return new RegexLiteral(pattern, flags);
                }
                case TEMPLATE_STRING -> {
                    advance();
                    return parseTemplate((JsTemplateString) t);
                }
                case IDENTIFIER -> {
                    // ContainsArguments: a class static block may not reference `arguments`, and the
                    // check reaches into arrow bodies and computed keys but not nested function bodies.
                    if (inStaticBlock && "arguments".equals(((JsIdentifier) t).getValue())) {
                        throw new SyntaxErrorException("'arguments' is not allowed in a class static block");
                    }
                    return parseIdentifierOrArrow();
                }
                case PRIVATE_IDENTIFIER -> {
                    advance();
                    // A private name stands alone only as the left operand of `#x in obj`.
                    if (!isKeyword("in")) {
                        throw error();
                    }
                    return referencePrivateName(((JsPrivateIdentifier) t).getValue());
                }
                case KEYWORD -> {
                    return parseKeywordPrimary();
                }
                case SEPARATOR -> {
                    return parseSeparatorPrimary();
                }
                default -> throw error();
            }
        }

        private Expression parseIdentifierOrArrow() {
            final var t = (JsIdentifier) current();
            // An escaped keyword lexes as an identifier so it can serve as an IdentifierName; in a
            // reference position it is still the reserved word, and so a SyntaxError.
            if (t.isEscaped() && isEscapeReserved(t.getValue())) {
                throw new SyntaxErrorException("Keyword must not contain escaped characters: " + t.getValue());
            }
            // An IdentifierReference is never a reserved word: `with`, `debugger`, `enum` and the
            // strict future-reserved words reach the parser as identifier tokens but are not usable.
            // `eval`/`arguments` are restricted only as binding and assignment targets, not as reads.
            // A contextual keyword (`async`/`of`, or `await` outside a reserved context) is a genuine
            // identifier here - `isEscapeReserved` already carries that distinction, unlike a blanket
            // `Lexer.isReservedWord` check which would wrongly reject an escaped `async`/`of`.
            if (isEscapeReserved(t.getValue()) || ParserTables.STRICT_RESERVED.contains(t.getValue())) {
                throw new SyntaxErrorException("'" + t.getValue() + "' cannot be used as an identifier in strict mode");
            }
            if (peek().getType() == JsType.OPERATOR && "=>".equals(((JsOperator) peek()).getValue())) {
                if (newlineBeforePeek()) {
                    throw error();
                }
                advance();
                advance();
                validateBindingName(t.getValue());
                return parseArrowBody(List.of(new Identifier(t.getValue())), false);
            }
            advance();
            return new Identifier(t.getValue());
        }

        private Expression parseKeywordPrimary() {
            final var kw = ((JsKeyword) current()).getValue();
            return switch (kw) {
                case "this" -> {
                    advance();
                    yield new ThisExpression();
                }
                case "function" -> parseFunctionExpression(false);
                case "async" -> asyncStartsFunctionOrArrow() ? parseAsyncPrimary() : parseContextualIdentifier("async");
                case "of" -> parseContextualIdentifier("of");
                case "await" -> {
                    if (awaitIsReserved()) {
                        throw error();
                    }
                    yield parseContextualIdentifier("await");
                }
                case "class" -> parseClassExpression();
                case "super" -> {
                    rejectOutsideFunctionCode("super");
                    advance();
                    if (isSeparator('(')) {
                        if (!superCallAllowed) {
                            throw new SyntaxErrorException(
                                    "'super' keyword unexpected here: a super call belongs to a derived constructor");
                        }
                    } else if (!superPropertyAllowed) {
                        throw new SyntaxErrorException(
                                "'super' keyword unexpected here: a super property belongs to a method");
                    }
                    yield new SuperExpression();
                }
                case "import" -> parseImportExpressionOrMeta();
                default -> throw error();
            };
        }

        // Dynamic `import(specifier)` and the `import.meta` meta-property. Reached from expression
        // position (and statement position via `isDynamicImportOrMeta`); static `import ...` declarations
        // never get here.
        private Expression parseImportExpressionOrMeta() {
            expectKeyword("import");
            if (matchOperator(".")) {
                if (!isContextualKeyword("meta")) {
                    throw error();
                }
                rejectInScriptGoal("import.meta");
                advance();
                return new MetaProperty("import", "meta");
            }
            expectSeparator('(');
            final var source = withInAllowed(this::parseAssignment);
            Expression options = null;
            if (matchSeparator(',') && !isSeparator(')')) {
                options = withInAllowed(this::parseAssignment);
                matchSeparator(',');
            }
            expectSeparator(')');
            return new ImportExpression(source, options);
        }

        private boolean isDynamicImportOrMeta() {
            final var next = peek();
            return (next.getType() == JsType.SEPARATOR && ((JsSeparator) next).getValue() == '(')
                    || (next.getType() == JsType.OPERATOR && ".".equals(((JsOperator) next).getValue()));
        }

        // `async` only modifies what follows it when a function or an arrow head does: `async(1)` is a
        // call of a function named async, and `async` alone is an ordinary identifier reference.
        private boolean asyncStartsFunctionOrArrow() {
            final var next = peek();
            if (next.getType() == JsType.KEYWORD && "function".equals(((JsKeyword) next).getValue())) {
                return !newlineBeforePeek();
            }
            if (newlineBeforePeek()) {
                return false;
            }
            if (next.getType() == JsType.SEPARATOR && ((JsSeparator) next).getValue() == '(') {
                return matchingParenFollowedByArrowFrom(pos + 1);
            }
            if (next.getType() != JsType.IDENTIFIER && !(next.getType() == JsType.KEYWORD
                    && CONTEXTUAL_KEYWORDS.contains(((JsKeyword) next).getValue()))) {
                return false;
            }
            final var after = peekAt(2);
            return after.getType() == JsType.OPERATOR && "=>".equals(((JsOperator) after).getValue());
        }

        private Expression parseAsyncPrimary() {
            expectKeyword("async");
            if (isKeyword("function")) {
                return parseFunctionExpression(true);
            }
            // An async arrow's parameters are AsyncArrowBindingIdentifier/CoverCallExpression under
            // [+Await], so `await` is reserved there even though the head is not yet the body.
            if (peek().getType() == JsType.OPERATOR && "=>".equals(((JsOperator) peek()).getValue())) {
                if (newlineBeforePeek()) {
                    throw error();
                }
                final var param = withAwaitReserved(true, this::parseBindingIdentifier);
                expectOperator("=>");
                return parseArrowBody(List.of(param), true);
            }
            if (isSeparator('(') && matchingParenFollowedByArrow()) {
                final var params = withAwaitReserved(true, this::parseParams);
                if (newlineBeforeCurrent()) {
                    throw error();
                }
                expectOperator("=>");
                return parseArrowBody(params, true);
            }
            throw error();
        }

        // the assignment is read by the production lambda, which PMD's dataflow cannot see
        @SuppressWarnings("PMD.UnusedAssignment")
        private <T> T withAwaitReserved(boolean reserved, Supplier<T> production) {
            final var wasAwaitReserved = awaitReserved;
            awaitReserved = reserved;
            try {
                return production.get();
            } finally {
                awaitReserved = wasAwaitReserved;
            }
        }

        // A FunctionExpression's own name is a BindingIdentifier[~Yield, ~Await] (and [+Await] only for
        // an async one), so `(function await(){})` is legal even inside a class static block.
        private FunctionExpression parseFunctionExpression(boolean async) {
            expectKeyword("function");
            final var generator = matchOperator("*");
            final var name = withAwaitReserved(async, () -> identifierLikeAt(0) ? parseBindingIdentifier() : null);
            final var parts = parseFunctionParts(generator, async);
            return new FunctionExpression(name, parts.params(), parts.body(), async, generator);
        }

        private ClassDeclaration parseClassDeclaration() {
            expectKeyword("class");
            final var id = parseBindingIdentifier();
            declareBoundNames(id, true);
            final var superClass = parseClassHeritage();
            return new ClassDeclaration(id, superClass, parseClassBody(superClass != null));
        }

        private ClassExpression parseClassExpression() {
            expectKeyword("class");
            Identifier id = null;
            if (identifierLikeAt(0) && isNotKeywordAt(0)) {
                id = parseBindingIdentifier();
            }
            final var superClass = parseClassHeritage();
            return new ClassExpression(id, superClass, parseClassBody(superClass != null));
        }

        // ClassHeritage is a LeftHandSideExpression, so an unparenthesised arrow function is a
        // Syntax Error there even though `class C extends (() => {}) {}` is fine.
        private Expression parseClassHeritage() {
            if (!matchKeyword("extends")) {
                return null;
            }
            if (startsArrowFunction()) {
                throw error();
            }
            return parseCallMember();
        }

        private boolean startsArrowFunction() {
            if (isSeparator('(')) {
                return matchingParenFollowedByArrow();
            }
            final var asyncPrefix = isKeyword("async") && !newlineBeforePeek();
            final var start = asyncPrefix ? 1 : 0;
            final var head = peekAt(start);
            if (head.getType() == JsType.IDENTIFIER) {
                final var next = peekAt(start + 1);
                return next.getType() == JsType.OPERATOR && "=>".equals(((JsOperator) next).getValue());
            }
            return asyncPrefix && head.getType() == JsType.SEPARATOR && ((JsSeparator) head).getValue() == '('
                    && matchingParenFollowedByArrowFrom(pos + 1);
        }

        // The private environment covers the class body only: a `#name` used in the heritage
        // belongs to the enclosing class, not to this one.
        private ClassBody parseClassBody(boolean hasHeritage) {
            final var enclosing = privateScope;
            privateScope = new PrivateScope(enclosing);
            try {
                final var body = parseClassBodyMembers(hasHeritage);
                privateScope.resolve();
                return body;
            } finally {
                privateScope = enclosing;
            }
        }

        private ClassBody parseClassBodyMembers(boolean hasHeritage) {
            final var wasClassHasHeritage = classHasHeritage;
            final var wasInNonArrowFunction = inNonArrowFunction;
            final var wasSuperPropertyAllowed = superPropertyAllowed;
            classHasHeritage = hasHeritage;
            // A field initializer and a static block are function-like code too, so `super` and
            // `new.target` reach them.
            inNonArrowFunction = true;
            superPropertyAllowed = true;
            expectSeparator('{');
            final var members = new ArrayList<JsNode>();
            final Map<String, Set<String>> privateNames = new HashMap<>();
            var seenConstructor = false;
            try {
                while (!isSeparator('}') && !atEnd()) {
                    if (matchSeparator(';')) {
                        continue;
                    }
                    final var member = parseClassMember(privateNames);
                    if (member instanceof MethodDefinition method && "constructor".equals(method.getKind())) {
                        if (seenConstructor) {
                            throw new SyntaxErrorException("A class may only have one constructor");
                        }
                        seenConstructor = true;
                    }
                    members.add(member);
                }
            } finally {
                classHasHeritage = wasClassHasHeritage;
                inNonArrowFunction = wasInNonArrowFunction;
                superPropertyAllowed = wasSuperPropertyAllowed;
            }
            expectSeparator('}');
            return new ClassBody(members);
        }

        private JsNode parseClassMember(Map<String, Set<String>> privateNames) {
            final var isStatic = matchContextualModifier("static");
            if (isStatic && isSeparator('{')) {
                return parseStaticBlock();
            }
            final var async = matchAsyncMethodModifier();
            final var generator = matchOperator("*");
            var kind = "method";
            if (!async && !generator) {
                if (matchContextualModifier("get")) {
                    kind = "get";
                } else if (matchContextualModifier("set")) {
                    kind = "set";
                }
            }
            final var memberKey = parseClassMemberKey();
            if (isSeparator('(')) {
                checkStaticMethodName(memberKey, isStatic);
                final var resolvedKind = resolveMethodKind(kind, memberKey, isStatic, async, generator);
                final var parts = parseFunctionParts(generator, async,
                        "constructor".equals(resolvedKind) && classHasHeritage, true);
                checkAccessorParams(resolvedKind, parts.params());
                final var value = new FunctionExpression(null, parts.params(), parts.body(), async, generator);
                declarePrivateName(privateNames, memberKey, resolvedKind, isStatic);
                return new MethodDefinition(memberKey.key(), value, resolvedKind, isStatic, memberKey.computed());
            }
            if (!"method".equals(kind) || async || generator) {
                throw error();
            }
            checkFieldName(memberKey, isStatic);
            declarePrivateName(privateNames, memberKey, "field", isStatic);
            Expression value = null;
            if (matchOperator("=")) {
                value = outsideGenerator(this::parseAssignment);
                if (containsArgumentsOrSuperCall(value)) {
                    throw new SyntaxErrorException(
                            "'arguments' and a bare 'super' call are not allowed in a class field initializer");
                }
            }
            consumeSemicolon();
            return new FieldDefinition(memberKey.key(), value, isStatic, memberKey.computed());
        }

        // A getter takes no parameters and a setter takes exactly one, which may carry a default but
        // may not be a rest element (PropertySetParameterList is a single FormalParameter).
        private void checkAccessorParams(String kind, List<JsNode> params) {
            if ("get".equals(kind) && !params.isEmpty()) {
                throw new SyntaxErrorException("Getter must not have any formal parameters");
            }
            if ("set".equals(kind) && (params.size() != 1 || params.getFirst() instanceof RestElement)) {
                throw new SyntaxErrorException("Setter must have exactly one formal parameter");
            }
        }

        // A static method cannot be named `prototype`: the class object already has that property.
        private void checkStaticMethodName(MemberKey memberKey, boolean isStatic) {
            if (isStatic && "prototype".equals(propName(memberKey))) {
                throw new SyntaxErrorException("Classes may not have a static method named 'prototype'");
            }
        }

        private static String propName(MemberKey memberKey) {
            if (memberKey.computed()) {
                return null;
            }
            if (memberKey.key() instanceof Identifier id) {
                return id.getName();
            }
            if (memberKey.key() instanceof StringLiteral str) {
                return str.getValue();
            }
            return null;
        }

        // An instance field cannot be named `constructor`, and a static field cannot be named
        // `prototype` — both would collide with the class's own machinery.
        private void checkFieldName(MemberKey memberKey, boolean isStatic) {
            final var name = propName(memberKey);
            if (name == null) {
                return;
            }
            if ("constructor".equals(name)) {
                throw new SyntaxErrorException("Classes may not have a field named 'constructor'");
            }
            if (isStatic && "prototype".equals(name)) {
                throw new SyntaxErrorException("Classes may not have a static field named 'prototype'");
            }
        }

        // A private name may be declared more than once only as exactly one getter and one setter
        // pair; any other repetition of the same #name is a Syntax Error.
        private void declarePrivateName(Map<String, Set<String>> privateNames, MemberKey memberKey, String kind,
                boolean isStatic) {
            if (!(memberKey.key() instanceof PrivateIdentifier priv)) {
                return;
            }
            if ("constructor".equals(priv.getName())) {
                throw new SyntaxErrorException("Classes may not have a private member named #constructor");
            }
            privateScope.declare(priv.getName());
            final var existing = privateNames.computeIfAbsent(priv.getName(), _ -> new HashSet<>());
            final var isDuplicate = switch (kind) {
                case "get" -> existing.contains("get") || existing.contains("field") || existing.contains("method");
                case "set" -> existing.contains("set") || existing.contains("field") || existing.contains("method");
                default -> !existing.isEmpty();
            };
            if (isDuplicate) {
                throw new SyntaxErrorException("Duplicate private name #" + priv.getName());
            }
            // The one legal repetition is a getter/setter pair, and the pair has to agree on `static`.
            if (!existing.isEmpty() && existing.contains(isStatic ? "instance" : "static")) {
                throw new SyntaxErrorException(
                        "A private getter and setter named #" + priv.getName() + " must agree on 'static'");
            }
            existing.add(kind);
            existing.add(isStatic ? "static" : "instance");
        }

        // ContainsArguments / ContainsSuperCall, approximated: recurse through ordinary expressions
        // and into arrow bodies (which have no `arguments`/`super` binding of their own), but treat a
        // nested function or class as an opaque boundary since it introduces its own bindings.
        private static boolean containsArgumentsOrSuperCall(JsNode node) {
            return switch (node) {
                case null -> false;
                case Identifier id -> "arguments".equals(id.getName());
                case CallExpression call ->
                    call.getCallee() instanceof SuperExpression || containsArgumentsOrSuperCall(call.getCallee())
                            || call.getArguments().stream().anyMatch(State::containsArgumentsOrSuperCall);
                case BinaryExpression bin ->
                    containsArgumentsOrSuperCall(bin.getLeft()) || containsArgumentsOrSuperCall(bin.getRight());
                case LogicalExpression log ->
                    containsArgumentsOrSuperCall(log.getLeft()) || containsArgumentsOrSuperCall(log.getRight());
                case ConditionalExpression cond ->
                    containsArgumentsOrSuperCall(cond.getTest()) || containsArgumentsOrSuperCall(cond.getConsequent())
                            || containsArgumentsOrSuperCall(cond.getAlternate());
                case UnaryExpression unary -> containsArgumentsOrSuperCall(unary.getArgument());
                case AssignmentExpression assign -> containsArgumentsOrSuperCall(assign.getValue());
                case SequenceExpression seq ->
                    seq.getExpressions().stream().anyMatch(State::containsArgumentsOrSuperCall);
                case SpreadElement spread -> containsArgumentsOrSuperCall(spread.getArgument());
                case ArrayExpression array ->
                    array.getElements().stream().anyMatch(State::containsArgumentsOrSuperCall);
                case ObjectExpression obj -> obj.getProperties().stream().anyMatch(State::containsArgumentsOrSuperCall);
                case Property prop -> containsArgumentsOrSuperCall(prop.getValue());
                case MemberExpression member -> containsArgumentsOrSuperCall(member.getObject())
                        || containsArgumentsOrSuperCall(member.getProperty());
                case ArrowFunctionExpression arrow -> containsArgumentsOrSuperCall(arrow.getBody());
                case BlockStatement block -> block.getBody().stream().anyMatch(State::containsArgumentsOrSuperCall);
                case ExpressionStatement stmt -> containsArgumentsOrSuperCall(stmt.getExpression());
                case ReturnStatement ret -> containsArgumentsOrSuperCall(ret.getArgument());
                case VariableDeclaration decl ->
                    decl.getDeclarations().stream().anyMatch(State::containsArgumentsOrSuperCall);
                case VariableDeclarator declarator -> containsArgumentsOrSuperCall(declarator.getInit());
                case IfStatement ifStmt -> containsArgumentsOrSuperCall(ifStmt.getTest())
                        || containsArgumentsOrSuperCall(ifStmt.getConsequent())
                        || containsArgumentsOrSuperCall(ifStmt.getAlternate());
                default -> false;
            };
        }

        // ArrowParameters Contains YieldExpression/AwaitExpression, approximated: recurse through
        // binding patterns and ordinary expressions, but treat a nested function/class/arrow as an
        // opaque boundary - a nested arrow already rejects its own params via this same check when
        // it was parsed, and a nested ordinary/generator/async function never contains a YieldExpression
        // or AwaitExpression scoped to the outer arrow's context in the first place.
        private static boolean containsYieldOrAwait(JsNode node) {
            return switch (node) {
                case null -> false;
                case YieldExpression ignored -> true;
                case AwaitExpression ignored -> true;
                case AssignmentPattern pattern ->
                    containsYieldOrAwait(pattern.getLeft()) || containsYieldOrAwait(pattern.getRight());
                case ArrayPattern pattern -> pattern.getElements().stream().anyMatch(State::containsYieldOrAwait);
                case ObjectPattern pattern -> pattern.getProperties().stream().anyMatch(State::containsYieldOrAwait);
                case RestElement rest -> containsYieldOrAwait(rest.getArgument());
                case Property prop ->
                    (prop.isComputed() && containsYieldOrAwait(prop.getKey())) || containsYieldOrAwait(prop.getValue());
                case AssignmentExpression assign ->
                    containsYieldOrAwait(assign.getTarget()) || containsYieldOrAwait(assign.getValue());
                case BinaryExpression bin ->
                    containsYieldOrAwait(bin.getLeft()) || containsYieldOrAwait(bin.getRight());
                case LogicalExpression log ->
                    containsYieldOrAwait(log.getLeft()) || containsYieldOrAwait(log.getRight());
                case ConditionalExpression cond -> containsYieldOrAwait(cond.getTest())
                        || containsYieldOrAwait(cond.getConsequent()) || containsYieldOrAwait(cond.getAlternate());
                case UnaryExpression unary -> containsYieldOrAwait(unary.getArgument());
                case UpdateExpression update -> containsYieldOrAwait(update.getArgument());
                case SequenceExpression seq -> seq.getExpressions().stream().anyMatch(State::containsYieldOrAwait);
                case SpreadElement spread -> containsYieldOrAwait(spread.getArgument());
                case ArrayExpression array -> array.getElements().stream().anyMatch(State::containsYieldOrAwait);
                case ObjectExpression obj -> obj.getProperties().stream().anyMatch(State::containsYieldOrAwait);
                case CallExpression call -> containsYieldOrAwait(call.getCallee())
                        || call.getArguments().stream().anyMatch(State::containsYieldOrAwait);
                case NewExpression newExpr -> containsYieldOrAwait(newExpr.getCallee())
                        || newExpr.getArguments().stream().anyMatch(State::containsYieldOrAwait);
                case MemberExpression member -> containsYieldOrAwait(member.getObject())
                        || (member.isComputed() && containsYieldOrAwait(member.getProperty()));
                case TemplateLiteral template ->
                    template.getExpressions().stream().anyMatch(State::containsYieldOrAwait);
                case TaggedTemplateExpression tagged ->
                    containsYieldOrAwait(tagged.getTag()) || containsYieldOrAwait(tagged.getQuasi());
                default -> false;
            };
        }

        private StaticBlock parseStaticBlock() {
            return inFunctionScope(List.of(), () -> inBreakableBoundary(() -> {
                inStaticBlock = true;
                return parseStaticBlockBody();
            }));
        }

        // ClassStaticBlockStatementList is StatementList[+Await], so `await` is reserved throughout,
        // even though a static block is not async code and an AwaitExpression is separately an error.
        private StaticBlock parseStaticBlockBody() {
            final var wasAwaitReserved = awaitReserved;
            awaitReserved = true;
            try {
                return parseStaticBlockStatements();
            } finally {
                awaitReserved = wasAwaitReserved;
            }
        }

        private StaticBlock parseStaticBlockStatements() {
            expectSeparator('{');
            final var body = outsideGenerator(() -> {
                final var statements = new ArrayList<Statement>();
                while (!isSeparator('}') && !atEnd()) {
                    statements.add(parseStatement());
                }
                return statements;
            });
            expectSeparator('}');
            return new StaticBlock(body);
        }

        private String resolveMethodKind(String kind, MemberKey memberKey, boolean isStatic, boolean async,
                boolean generator) {
            if (isStatic || !isNamedConstructor(memberKey)) {
                return "method".equals(kind) ? "method" : kind;
            }
            if (async || generator || !"method".equals(kind)) {
                throw new SyntaxErrorException("A class constructor may not be a generator, async or an accessor");
            }
            return "constructor";
        }

        private boolean isNamedConstructor(MemberKey memberKey) {
            if (memberKey.computed()) {
                return false;
            }
            return memberKey.key() instanceof Identifier id && "constructor".equals(id.getName())
                    || memberKey.key() instanceof StringLiteral str && "constructor".equals(str.getValue());
        }

        private MemberKey parseClassMemberKey() {
            if (isSeparator('[')) {
                advance();
                final var key = withInAllowed(this::parseAssignment);
                expectSeparator(']');
                return new MemberKey(key, true);
            }
            final var t = current();
            final Expression key = switch (t.getType()) {
                case STRING -> new StringLiteral(((JsString) t).getValue());
                case NUMBER -> new NumberLiteral(((JsNumber) t).getValue());
                case BIGINT -> new StringLiteral(((JsBigInt) t).getValue().toString());
                case PRIVATE_IDENTIFIER -> new PrivateIdentifier(((JsPrivateIdentifier) t).getValue());
                default -> literalPropertyKey(t);
            };
            advance();
            return new MemberKey(key, false);
        }

        // static/get/set are contextual: they modify a member only when the next token begins a
        // member key. When followed by '(', '=', ';' or '}' the word is itself the member name.
        private boolean matchContextualModifier(String name) {
            final var t = current();
            if (t.getType() != JsType.IDENTIFIER || !((JsIdentifier) t).getValue().equals(name)
                    || ((JsIdentifier) t).isEscaped()) {
                return false;
            }
            final var next = peek();
            if (next.getType() == JsType.OPERATOR && "=".equals(((JsOperator) next).getValue())) {
                return false;
            }
            // A GeneratorMethod's `*` can never follow `get`/`set`'s own ClassElementName production
            // (unlike `static`, which a static generator method legitimately follows with one), so
            // `get`/`set` immediately before `*` must be the member's own name instead - e.g.
            // `get\n*a(){}` is a field named "get" (closed by ASI on the intervening newline) plus a
            // separate generator method "a", not a getter accessor.
            if (("get".equals(name) || "set".equals(name)) && next.getType() == JsType.OPERATOR
                    && "*".equals(((JsOperator) next).getValue())) {
                return false;
            }
            if (next.getType() == JsType.SEPARATOR) {
                final char c = ((JsSeparator) next).getValue();
                if (c == '(' || c == ';' || c == '}') {
                    return false;
                }
            }
            advance();
            return true;
        }

        // async is a keyword, contextual here: it modifies a member only when a member key (or `*`)
        // follows. Followed by '(', '=', ';' or '}' the word is the member name itself.
        private boolean matchAsyncMethodModifier() {
            final var t = current();
            if (t.getType() != JsType.KEYWORD || !"async".equals(((JsKeyword) t).getValue())) {
                return false;
            }
            final var next = peek();
            if (next.getType() == JsType.OPERATOR && "=".equals(((JsOperator) next).getValue())) {
                return false;
            }
            if (next.getType() == JsType.SEPARATOR) {
                final char c = ((JsSeparator) next).getValue();
                if (c == '(' || c == ';' || c == '}') {
                    return false;
                }
            }
            advance();
            return true;
        }

        private record MemberKey(Expression key, boolean computed) {
        }

        private Expression parseSeparatorPrimary() {
            final char c = ((JsSeparator) current()).getValue();
            return switch (c) {
                case '(' -> parseParenOrArrow();
                case '[' -> parseArray();
                case '{' -> parseObject();
                default -> throw error();
            };
        }

        private Expression parseParenOrArrow() {
            if (matchingParenFollowedByArrow()) {
                final var params = parseParams();
                if (newlineBeforeCurrent()) {
                    throw error();
                }
                expectOperator("=>");
                return parseArrowBody(params, false);
            }
            expectSeparator('(');
            final var expr = withInAllowed(this::parseExpression);
            expectSeparator(')');
            parenthesised.add(expr);
            // Parentheses make a literal a PrimaryExpression, so `({}) = x` is an early error even
            // though the identical unparenthesised `{} = x` is a destructuring assignment.
            if (isOperator("=") && (expr instanceof ObjectExpression || expr instanceof ArrayExpression)) {
                throw new SyntaxErrorException("Invalid destructuring assignment target");
            }
            return expr;
        }

        // A '(' begins arrow params only if the matching ')' is immediately followed by '=>'; otherwise it is grouping.
        private boolean matchingParenFollowedByArrow() {
            return matchingParenFollowedByArrowFrom(pos);
        }

        private boolean matchingParenFollowedByArrowFrom(int from) {
            var depth = 0;
            final var size = tokens.size();
            for (var i = from; i < size; i++) {
                final var t = tokens.get(i);
                if (t.getType() == JsType.SEPARATOR) {
                    final char c = ((JsSeparator) t).getValue();
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        if (depth == 0) {
                            final var next = tokens.get(Math.min(i + 1, size - 1));
                            return next.getType() == JsType.OPERATOR && "=>".equals(((JsOperator) next).getValue());
                        }
                    }
                }
            }
            return false;
        }

        private ArrowFunctionExpression parseArrowBody(List<JsNode> params, boolean async) {
            // ArrowFunction : ArrowParameters => ConciseBody - it is a Syntax Error if ArrowParameters
            // Contains YieldExpression/AwaitExpression: the cover grammar parses the parenthesized
            // head with the enclosing [Yield]/[Await] parameter still in effect (arrows never reset
            // it themselves), so a `yield`/`await` there is parsed as those expressions whenever the
            // surrounding generator/async context allows it, and is rejected only afterwards, here.
            if (params.stream().anyMatch(State::containsYieldOrAwait)) {
                throw new SyntaxErrorException("Arrow parameters may not contain 'yield' or 'await' expressions");
            }
            final var wasInFunctionBody = inFunctionBody;
            final var wasAwaitReserved = awaitReserved;
            inFunctionBody = true;
            awaitReserved = async;
            try {
                return inFunctionKind(async, () -> inBreakableBoundary(() -> {
                    if (isSeparator('{')) {
                        final var body = inFunctionScope(params, this::parseBlockBody);
                        checkUseStrictWithSimpleParams(params, body);
                        return new ArrowFunctionExpression(params, body, false, async);
                    }
                    return new ArrowFunctionExpression(params, parseAssignment(), true, async);
                }));
            } finally {
                inFunctionBody = wasInFunctionBody;
                awaitReserved = wasAwaitReserved;
            }
        }

        private ArrayExpression parseArray() {
            expectSeparator('[');
            final var elements = new ArrayList<Expression>();
            var trailingComma = false;
            while (!isSeparator(']')) {
                if (matchSeparator(',')) {
                    elements.add(null);
                    trailingComma = false;
                    continue;
                }
                elements.add(parseSpreadableExpression());
                trailingComma = false;
                if (!isSeparator(']')) {
                    expectSeparator(',');
                    trailingComma = true;
                }
            }
            expectSeparator(']');
            return new ArrayExpression(elements, trailingComma);
        }

        private ObjectExpression parseObject() {
            expectSeparator('{');
            final var properties = new ArrayList<JsNode>();
            var trailingComma = false;
            if (!isSeparator('}')) {
                do {
                    if (isSeparator('}')) {
                        break;
                    }
                    properties.add(parseObjectMember());
                    trailingComma = matchSeparator(',');
                } while (trailingComma);
            }
            expectSeparator('}');
            final var object = new ObjectExpression(properties, trailingComma);
            if (hasDuplicateProto(properties)) {
                duplicateProto.add(object);
            }
            return object;
        }

        // An object literal may carry at most one `__proto__: value` property: the second one would be
        // a second [[Prototype]] setter. The same text is legal as a destructuring pattern, so the
        // error is raised only where the literal is not reinterpreted as an assignment target.
        private static boolean hasDuplicateProto(List<JsNode> properties) {
            var seen = false;
            for (final var member : properties) {
                if (!(member instanceof Property property) || property.isComputed() || property.isShorthand()
                        || !"init".equals(property.getKind()) || !"__proto__".equals(propertyKeyName(property))) {
                    continue;
                }
                if (seen) {
                    return true;
                }
                seen = true;
            }
            return false;
        }

        private static String propertyKeyName(Property property) {
            return switch (property.getKey()) {
                case Identifier id -> id.getName();
                case StringLiteral str -> str.getValue();
                case null, default -> null;
            };
        }

        private JsNode parseObjectMember() {
            if (matchOperator("...")) {
                return new SpreadElement(withInAllowed(this::parseAssignment));
            }
            return withInAllowed(this::parseProperty);
        }

        private Property parseProperty() {
            var async = false;
            if (isKeyword("async") && starOrKeyFollows() && !newlineBeforePeek()) {
                advance();
                async = true;
            }
            final var generator = matchOperator("*");
            var kind = "init";
            if (!async && !generator && (isIdentifier("get") || isIdentifier("set")) && beginsPropertyKey(peek())) {
                kind = ((JsIdentifier) current()).getValue();
                advance();
            }
            final var computed = isSeparator('[');
            final var identifierToken = current().getType() == JsType.IDENTIFIER;
            // A shorthand property's value is an IdentifierReference, so a keyword token that is
            // presently usable as one (`await` outside async code; `of`/`async` always) is just as
            // eligible as a genuine identifier token - `literalPropertyKey` already turns either into
            // the same `Identifier` node used below as both key and value.
            final var fromIdentifier = identifierToken
                    || current().getType() == JsType.KEYWORD && keywordIsIdentifier();
            final var escapedReserved = identifierToken && ((JsIdentifier) current()).isEscaped()
                    && isReservedWord(((JsIdentifier) current()).getValue());
            final var key = parsePropertyKey();
            if (isSeparator('(')) {
                final var parts = parseFunctionParts(generator, async, false, true);
                checkAccessorParams(kind, parts.params());
                final var value = new FunctionExpression(null, parts.params(), parts.body(), async, generator);
                return new Property(key, value, computed, false, "init".equals(kind) ? "method" : kind);
            }
            if (async || generator || !"init".equals(kind)) {
                throw error();
            }
            if (matchOperator(":")) {
                return new Property(key, parseAssignment(), computed, false);
            }
            if (!computed && fromIdentifier) {
                // CoverInitializedName: `{a = 1}` is only valid as a destructuring pattern, but a
                // single-token lookahead cannot tell it apart from an object literal, so keep it as
                // an assignment-valued shorthand and let toAssignmentPattern reinterpret it.
                // A shorthand property's value is an IdentifierReference, so an escaped reserved
                // word is a SyntaxError here even though it is a legal IdentifierName as the key.
                if (escapedReserved) {
                    throw new SyntaxErrorException(
                            "Keyword must not contain escaped characters: " + ((Identifier) key).getName());
                }
                if (matchOperator("=")) {
                    return new Property(key, new AssignmentExpression("=", key, parseAssignment()), false, true);
                }
                return new Property(key, key, false, true);
            }
            throw error();
        }

        private Expression parsePropertyKey() {
            if (isSeparator('[')) {
                advance();
                final var key = parseAssignment();
                expectSeparator(']');
                return key;
            }
            final var t = current();
            final Expression key = switch (t.getType()) {
                case STRING -> new StringLiteral(((JsString) t).getValue());
                case NUMBER -> new NumberLiteral(((JsNumber) t).getValue());
                case BIGINT -> new StringLiteral(((JsBigInt) t).getValue().toString());
                default -> literalPropertyKey(t);
            };
            advance();
            return key;
        }

        private boolean beginsPropertyKey(JsBaseElement t) {
            return switch (t.getType()) {
                // `true`/`false`/`null`/`undefined` are ordinary IdentifierNames grammar-wise even
                // though the lexer turns them into value tokens rather than keywords (see
                // identifierName's comment), so each is a legal property key on its own here too.
                case IDENTIFIER, KEYWORD, STRING, NUMBER, BOOLEAN, NULL, UNDEFINED -> true;
                case SEPARATOR -> ((JsSeparator) t).getValue() == '[';
                default -> false;
            };
        }

        private boolean starOrKeyFollows() {
            final var next = peek();
            return (next.getType() == JsType.OPERATOR && "*".equals(((JsOperator) next).getValue()))
                    || beginsPropertyKey(next);
        }

        private boolean isIdentifier(String name) {
            final var t = current();
            return t.getType() == JsType.IDENTIFIER && ((JsIdentifier) t).getValue().equals(name)
                    && !((JsIdentifier) t).isEscaped();
        }

        private TemplateLiteral parseTemplate(JsTemplateString template) {
            final var expressions = new ArrayList<Expression>();
            for (final var expressionTokens : template.getExpressions()) {
                expressions.add(forTemplateExpression(expressionTokens).parseTemplateExpression());
            }
            return new TemplateLiteral(template.getQuasis(), template.getRawQuasis(), expressions);
        }

        // A template's substitutions are lexed into their own token lists, so the nested parser has to
        // inherit the grammar context the template itself sits in.
        private State forTemplateExpression(List<JsBaseElement> tokens) {
            final var nested = new State(tokens, null, null, strictScriptGoal);
            nested.inGenerator = inGenerator;
            nested.inAsync = inAsync;
            nested.superCallAllowed = superCallAllowed;
            nested.inStaticBlock = inStaticBlock;
            nested.classHasHeritage = classHasHeritage;
            nested.privateScope = privateScope;
            nested.inFunctionBody = inFunctionBody;
            nested.inNonArrowFunction = inNonArrowFunction;
            nested.awaitReserved = awaitReserved;
            return nested;
        }

        private Expression parseTemplateExpression() {
            final var expr = parseExpression();
            if (!atEnd()) {
                throw error();
            }
            return expr;
        }
    }
}

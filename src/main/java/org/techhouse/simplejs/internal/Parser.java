package org.techhouse.simplejs.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
        return new State(tokens, null, null).parseProgram();
    }

    public static Program parse(Lexer.LexResult lexed) {
        return new State(lexed.tokens(), lexed.positions(), lexed.newlineBefore()).parseProgram();
    }

    // The recursive-descent walk is inherently stateful (a moving cursor over the token stream),
    // so the grammar productions live in this nested type. The cursor and token-level primitives
    // are inherited from TokenStream; the shared precedence tables live in ParserTables and the
    // cover-grammar reinterpretation in PatternConverter.
    private record FunctionParts(List<JsNode> params, BlockStatement body) {
    }

    private static final class State extends TokenStream {
        private final PatternConverter patterns = new PatternConverter(this);
        private DeclarationScope scope = new DeclarationScope(null, true);
        private boolean inStaticBlock;
        private boolean inGenerator;
        private boolean inAsync = true;
        private boolean superCallAllowed;
        private boolean classHasHeritage;

        private State(List<JsBaseElement> tokens, List<SourcePosition> positions, List<Boolean> newlineBefore) {
            super(tokens, positions, newlineBefore);
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
            return parseFunctionParts(generator, async, false);
        }

        private FunctionParts parseFunctionParts(boolean generator, boolean async, boolean superCall) {
            final var wasInStaticBlock = inStaticBlock;
            final var wasInGenerator = inGenerator;
            final var wasInAsync = inAsync;
            final var wasSuperCallAllowed = superCallAllowed;
            inStaticBlock = false;
            superCallAllowed = superCall;
            try {
                final var params = outsideGenerator(this::parseParams);
                setFunctionKind(generator, async);
                final var body = inFunctionScope(params, this::parseBlockBody);
                checkUseStrictWithSimpleParams(params, body);
                return new FunctionParts(params, body);
            } finally {
                inStaticBlock = wasInStaticBlock;
                inGenerator = wasInGenerator;
                inAsync = wasInAsync;
                superCallAllowed = wasSuperCallAllowed;
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
                        if (peek().getType() == JsType.KEYWORD && "function".equals(((JsKeyword) peek()).getValue())) {
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
            if (isUsingDeclarationStart()) {
                return parseUsingDeclaration();
            }
            if (isAwaitUsingDeclarationStart()) {
                return parseAwaitUsingDeclaration();
            }
            if (current().getType() == JsType.IDENTIFIER && peek().getType() == JsType.OPERATOR
                    && ":".equals(((JsOperator) peek()).getValue())) {
                return parseLabeledStatement();
            }
            return parseExpressionStatement();
        }

        // `using x = e` — contextual: a declaration only when `using` is directly followed by a binding
        // identifier (so `using`, `using.foo()`, `using = 1`, `let using = 1` still parse as expressions).
        private boolean isUsingDeclarationStart() {
            return isContextualKeyword("using") && peek().getType() == JsType.IDENTIFIER;
        }

        // `await using x = e` — three-token lookahead; otherwise `await` stays an AwaitExpression.
        private boolean isAwaitUsingDeclarationStart() {
            return isKeyword("await") && peek().getType() == JsType.IDENTIFIER
                    && "using".equals(((JsIdentifier) peek()).getValue()) && peekAt(2).getType() == JsType.IDENTIFIER;
        }

        private VariableDeclaration parseUsingDeclaration() {
            expectContextualKeyword("using");
            return new VariableDeclaration("using", parseUsingDeclarators());
        }

        private VariableDeclaration parseAwaitUsingDeclaration() {
            expectKeyword("await");
            expectContextualKeyword("using");
            return new VariableDeclaration("await using", parseUsingDeclarators());
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
            return new LabeledStatement(label, parseNestedStatement());
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
            final var body = parseNestedStatement();
            return new WhileStatement(test, body);
        }

        private DoWhileStatement parseDoWhile() {
            expectKeyword("do");
            final var body = parseNestedStatement();
            expectKeyword("while");
            expectSeparator('(');
            final var test = parseExpression();
            expectSeparator(')');
            consumeSemicolon();
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
            final var init = parseForHeaderLeft();
            if (isKeyword("in") || isKeyword("of")) {
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
            final var body = parseNestedStatement();
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
            final var body = parseNestedStatement();
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

        private CatchClause parseCatch() {
            expectKeyword("catch");
            JsNode param = null;
            if (matchSeparator('(')) {
                param = parseBindingTarget();
                expectSeparator(')');
            }
            final var body = parseBlock();
            return new CatchClause(param, body);
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
            final var cases = inScope(() -> {
                final var parsed = new ArrayList<SwitchCase>();
                while (!isSeparator('}') && !atEnd()) {
                    parsed.add(parseSwitchCase());
                }
                return parsed;
            });
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
            consumeSemicolon();
            return new BreakStatement(label);
        }

        private ContinueStatement parseContinue() {
            expectKeyword("continue");
            final var label = current().getType() == JsType.IDENTIFIER && !newlineBeforeCurrent()
                    ? parseIdentifier()
                    : null;
            consumeSemicolon();
            return new ContinueStatement(label);
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
            if (t.getType() != JsType.IDENTIFIER) {
                throw error();
            }
            advance();
            return new Identifier(((JsIdentifier) t).getValue());
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
            final var id = parseIdentifier();
            validateBindingName(id.getName());
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
            if (current() instanceof JsIdentifier token && token.isEscaped()
                    && Lexer.isReservedWord(token.getValue())) {
                throw new SyntaxErrorException("Keyword must not contain escaped characters: " + token.getValue());
            }
        }

        private static void validateBindingName(String name) {
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
            final Expression key = switch (t.getType()) {
                case IDENTIFIER -> new Identifier(((JsIdentifier) t).getValue());
                case STRING -> new StringLiteral(((JsString) t).getValue());
                case NUMBER -> new NumberLiteral(((JsNumber) t).getValue());
                case KEYWORD -> new Identifier(((JsKeyword) t).getValue());
                default -> throw error();
            };
            advance();
            if (matchOperator(":")) {
                return new Property(key, parseBindingElement(), false, false);
            }
            if (t.getType() != JsType.IDENTIFIER) {
                throw error();
            }
            assert key instanceof Identifier;
            validateBindingName(((Identifier) key).getName());
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
                final var consequent = parseAssignment();
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
                final int nextMinPrec = "**".equals(op) ? prec : prec + 1;
                final var right = parseBinary(nextMinPrec);
                left = ParserTables.LOGICAL_OPERATORS.contains(op)
                        ? new LogicalExpression(op, left, right)
                        : new BinaryExpression(op, left, right);
                op = currentBinaryOperator();
            }
            return left;
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
                    return new UnaryExpression(op, parseUnary(), true);
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
                    return new UnaryExpression(kw, argument, true);
                }
                if ("typeof".equals(kw) || "void".equals(kw)) {
                    advance();
                    return new UnaryExpression(kw, parseUnary(), true);
                }
                if ("await".equals(kw)) {
                    advance();
                    if (!inAsync) {
                        throw new SyntaxErrorException("await is only valid inside an async function");
                    }
                    return new AwaitExpression(parseUnary());
                }
            }
            return parsePostfix();
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
        }

        private Expression parseCallMember() {
            final var base = isKeyword("new") ? parseNew() : parsePrimary();
            return parseCallMemberTail(base);
        }

        private Expression parseCallMemberTail(Expression start) {
            var expr = start;
            var advancing = true;
            while (advancing) {
                if (matchOperator(".")) {
                    expr = new MemberExpression(expr, parseMemberProperty(), false, false);
                } else if (matchOperator("?.")) {
                    expr = parseOptionalTail(expr);
                } else if (isSeparator('[')) {
                    advance();
                    final var property = parseExpression();
                    expectSeparator(']');
                    expr = new MemberExpression(expr, property, true, false);
                } else if (isSeparator('(')) {
                    expr = new CallExpression(expr, parseArguments());
                } else if (current().getType() == JsType.TEMPLATE_STRING) {
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
            return new MemberExpression(object, parseMemberProperty(), false, true);
        }

        private Expression parseMemberProperty() {
            final var t = current();
            if (t.getType() == JsType.IDENTIFIER) {
                advance();
                return new Identifier(((JsIdentifier) t).getValue());
            }
            if (t.getType() == JsType.KEYWORD) {
                advance();
                return new Identifier(((JsKeyword) t).getValue());
            }
            if (t.getType() == JsType.PRIVATE_IDENTIFIER) {
                advance();
                return new PrivateIdentifier(((JsPrivateIdentifier) t).getValue());
            }
            throw error();
        }

        private Expression parseNew() {
            expectKeyword("new");
            if (matchOperator(".")) {
                if (!isContextualKeyword("target")) {
                    throw error();
                }
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
                    expr = new MemberExpression(expr, parseMemberProperty(), false, false);
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
                    return new PrivateIdentifier(((JsPrivateIdentifier) t).getValue());
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
            if (t.isEscaped() && Lexer.isReservedWord(t.getValue())) {
                throw new SyntaxErrorException("Keyword must not contain escaped characters: " + t.getValue());
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
                case "async" -> parseAsyncPrimary();
                case "class" -> parseClassExpression();
                case "super" -> {
                    advance();
                    if (isSeparator('(') && !superCallAllowed) {
                        throw new SyntaxErrorException(
                                "'super' keyword unexpected here: a super call belongs to a derived constructor");
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

        private Expression parseAsyncPrimary() {
            expectKeyword("async");
            if (isKeyword("function")) {
                return parseFunctionExpression(true);
            }
            if (current().getType() == JsType.IDENTIFIER && peek().getType() == JsType.OPERATOR
                    && "=>".equals(((JsOperator) peek()).getValue())) {
                if (newlineBeforePeek()) {
                    throw error();
                }
                final var param = parseBindingIdentifier();
                expectOperator("=>");
                return parseArrowBody(List.of(param), true);
            }
            if (isSeparator('(') && matchingParenFollowedByArrow()) {
                final var params = parseParams();
                if (newlineBeforeCurrent()) {
                    throw error();
                }
                expectOperator("=>");
                return parseArrowBody(params, true);
            }
            throw error();
        }

        private FunctionExpression parseFunctionExpression(boolean async) {
            expectKeyword("function");
            final var generator = matchOperator("*");
            Identifier name = null;
            if (current().getType() == JsType.IDENTIFIER) {
                name = parseBindingIdentifier();
            }
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
            if (current().getType() == JsType.IDENTIFIER) {
                id = parseBindingIdentifier();
            }
            final var superClass = parseClassHeritage();
            return new ClassExpression(id, superClass, parseClassBody(superClass != null));
        }

        private Expression parseClassHeritage() {
            return matchKeyword("extends") ? parseCallMember() : null;
        }

        private ClassBody parseClassBody(boolean hasHeritage) {
            final var wasClassHasHeritage = classHasHeritage;
            classHasHeritage = hasHeritage;
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
                final var resolvedKind = resolveMethodKind(kind, memberKey, isStatic, async, generator);
                final var parts = parseFunctionParts(generator, async,
                        "constructor".equals(resolvedKind) && classHasHeritage);
                final var value = new FunctionExpression(null, parts.params(), parts.body(), async, generator);
                declarePrivateName(privateNames, memberKey, resolvedKind);
                return new MethodDefinition(memberKey.key(), value, resolvedKind, isStatic, memberKey.computed());
            }
            if (!"method".equals(kind) || async || generator) {
                throw error();
            }
            checkFieldName(memberKey, isStatic);
            declarePrivateName(privateNames, memberKey, "field");
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

        // An instance field cannot be named `constructor`, and a static field cannot be named
        // `prototype` — both would collide with the class's own machinery.
        private void checkFieldName(MemberKey memberKey, boolean isStatic) {
            if (memberKey.computed()) {
                return;
            }
            final String name;
            if (memberKey.key() instanceof Identifier id) {
                name = id.getName();
            } else if (memberKey.key() instanceof StringLiteral str) {
                name = str.getValue();
            } else {
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
        private void declarePrivateName(Map<String, Set<String>> privateNames, MemberKey memberKey, String kind) {
            if (!(memberKey.key() instanceof PrivateIdentifier priv)) {
                return;
            }
            final var existing = privateNames.computeIfAbsent(priv.getName(), _ -> new HashSet<>());
            final var isDuplicate = switch (kind) {
                case "get" -> existing.contains("get") || existing.contains("field") || existing.contains("method");
                case "set" -> existing.contains("set") || existing.contains("field") || existing.contains("method");
                default -> !existing.isEmpty();
            };
            if (isDuplicate) {
                throw new SyntaxErrorException("Duplicate private name #" + priv.getName());
            }
            existing.add(kind);
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

        private StaticBlock parseStaticBlock() {
            return inFunctionScope(List.of(), () -> {
                inStaticBlock = true;
                return parseStaticBlockBody();
            });
        }

        private StaticBlock parseStaticBlockBody() {
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
                case IDENTIFIER -> new Identifier(((JsIdentifier) t).getValue());
                case KEYWORD -> new Identifier(((JsKeyword) t).getValue());
                case STRING -> new StringLiteral(((JsString) t).getValue());
                case NUMBER -> new NumberLiteral(((JsNumber) t).getValue());
                case PRIVATE_IDENTIFIER -> new PrivateIdentifier(((JsPrivateIdentifier) t).getValue());
                default -> throw error();
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
            return expr;
        }

        // A '(' begins arrow params only if the matching ')' is immediately followed by '=>'; otherwise it is grouping.
        private boolean matchingParenFollowedByArrow() {
            var depth = 0;
            final var size = tokens.size();
            for (var i = pos; i < size; i++) {
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
            return inFunctionKind(async, () -> {
                if (isSeparator('{')) {
                    final var body = inFunctionScope(params, this::parseBlockBody);
                    checkUseStrictWithSimpleParams(params, body);
                    return new ArrowFunctionExpression(params, body, false, async);
                }
                return new ArrowFunctionExpression(params, parseAssignment(), true, async);
            });
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
            return new ObjectExpression(properties, trailingComma);
        }

        private JsNode parseObjectMember() {
            if (matchOperator("...")) {
                return new SpreadElement(withInAllowed(this::parseAssignment));
            }
            return withInAllowed(this::parseProperty);
        }

        private Property parseProperty() {
            var async = false;
            if (isKeyword("async") && starOrKeyFollows()) {
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
            final var fromIdentifier = current().getType() == JsType.IDENTIFIER;
            final var escapedReserved = fromIdentifier && ((JsIdentifier) current()).isEscaped()
                    && isReservedWord(((JsIdentifier) current()).getValue());
            final var key = parsePropertyKey();
            if (isSeparator('(')) {
                final var parts = parseFunctionParts(generator, async);
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
                case IDENTIFIER -> new Identifier(((JsIdentifier) t).getValue());
                case STRING -> new StringLiteral(((JsString) t).getValue());
                case NUMBER -> new NumberLiteral(((JsNumber) t).getValue());
                case KEYWORD -> new Identifier(((JsKeyword) t).getValue());
                default -> throw error();
            };
            advance();
            return key;
        }

        private boolean beginsPropertyKey(JsBaseElement t) {
            return switch (t.getType()) {
                case IDENTIFIER, KEYWORD, STRING, NUMBER -> true;
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
            final var nested = new State(tokens, null, null);
            nested.inGenerator = inGenerator;
            nested.inAsync = inAsync;
            nested.superCallAllowed = superCallAllowed;
            nested.inStaticBlock = inStaticBlock;
            nested.classHasHeritage = classHasHeritage;
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

package org.techhouse.simplejs.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
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
import org.techhouse.simplejs.nodes.ImportNamespaceSpecifier;
import org.techhouse.simplejs.nodes.ImportSpecifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
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
    private static final Set<String> ASSIGNMENT_OPERATORS = Set.of("=", "+=", "-=", "*=", "/=", "%=", "**=", "<<=",
            ">>=", ">>>=", "&=", "|=", "^=", "&&=", "||=", "??=");

    private static final Set<String> PREFIX_UNARY_OPERATORS = Set.of("!", "~", "+", "-");

    private static final Set<String> LOGICAL_OPERATORS = Set.of("&&", "||", "??");

    private static final Map<String, Integer> BINARY_PRECEDENCE = Map.ofEntries(Map.entry("??", 1), Map.entry("||", 2),
            Map.entry("&&", 3), Map.entry("|", 4), Map.entry("^", 5), Map.entry("&", 6), Map.entry("==", 7),
            Map.entry("!=", 7), Map.entry("===", 7), Map.entry("!==", 7), Map.entry("<", 8), Map.entry("<=", 8),
            Map.entry(">", 8), Map.entry(">=", 8), Map.entry("instanceof", 8), Map.entry("in", 8), Map.entry("<<", 9),
            Map.entry(">>", 9), Map.entry(">>>", 9), Map.entry("+", 10), Map.entry("-", 10), Map.entry("*", 11),
            Map.entry("/", 11), Map.entry("%", 11), Map.entry("**", 12));

    private Parser() {
    }

    public static Program parse(List<JsBaseElement> tokens) {
        return new State(tokens, null).parseProgram();
    }

    public static Program parse(Lexer.LexResult lexed) {
        return new State(lexed.tokens(), lexed.positions()).parseProgram();
    }

    // The recursive-descent walk is inherently stateful (a moving cursor over the token
    // stream), so the mutable parsing state lives in this nested type while Parser stays a
    // stateless utility. The shared precedence tables above are reachable as enclosing statics.
    private static final class State {
        private final List<JsBaseElement> tokens;
        private final List<SourcePosition> positions;
        private final Deque<Boolean> noInStack = new ArrayDeque<>();
        private int pos;

        private State(List<JsBaseElement> tokens, List<SourcePosition> positions) {
            this.tokens = tokens;
            this.positions = positions;
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
                        return parseImportDeclaration();
                    }
                    case "export" -> {
                        return parseExportDeclaration();
                    }
                    default -> {
                    }
                }
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
                final var id = parseIdentifier();
                expectOperator("=");
                declarations.add(new VariableDeclarator(id, parseAssignment()));
            } while (matchSeparator(','));
            consumeOptionalSemicolon();
            return declarations;
        }

        private LabeledStatement parseLabeledStatement() {
            final var label = parseIdentifier();
            expectOperator(":");
            return new LabeledStatement(label, parseStatement());
        }

        private BlockStatement parseBlock() {
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
                declarations.add(new VariableDeclarator(id, init));
            } while (matchSeparator(','));
            consumeOptionalSemicolon();
            return new VariableDeclaration(kind, declarations);
        }

        private IfStatement parseIf() {
            expectKeyword("if");
            expectSeparator('(');
            final var test = parseExpression();
            expectSeparator(')');
            final var consequent = parseStatement();
            Statement alternate = null;
            if (matchKeyword("else")) {
                alternate = parseStatement();
            }
            return new IfStatement(test, consequent, alternate);
        }

        private WhileStatement parseWhile() {
            expectKeyword("while");
            expectSeparator('(');
            final var test = parseExpression();
            expectSeparator(')');
            final var body = parseStatement();
            return new WhileStatement(test, body);
        }

        private DoWhileStatement parseDoWhile() {
            expectKeyword("do");
            final var body = parseStatement();
            expectKeyword("while");
            expectSeparator('(');
            final var test = parseExpression();
            expectSeparator(')');
            consumeOptionalSemicolon();
            return new DoWhileStatement(body, test);
        }

        private Statement parseFor() {
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
            final var isUsingHead = init instanceof VariableDeclaration declaration
                    && "using".equals(declaration.getKind());
            if (isUsingHead && !isKeyword("of")) {
                throw error();
            }
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
                return parseForUsingDeclaration();
            }
            return withNoIn(this::parseExpression);
        }

        private VariableDeclaration parseForUsingDeclaration() {
            expectContextualKeyword("using");
            final var id = parseIdentifier();
            return new VariableDeclaration("using", List.of(new VariableDeclarator(id, null)));
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
                    declarations.add(new VariableDeclarator(id, init));
                } while (matchSeparator(','));
                return new VariableDeclaration(kind, declarations);
            });
        }

        private Statement parseForInOf(JsNode left, boolean isAwait) {
            final var target = left instanceof ArrayExpression || left instanceof ObjectExpression
                    ? toAssignmentPattern((Expression) left)
                    : left;
            validateForInOfTarget(target);
            final var isOf = "of".equals(((JsKeyword) current()).getValue());
            if (isAwait && !isOf) {
                throw error();
            }
            advance();
            final var right = isOf ? parseAssignment() : parseExpression();
            expectSeparator(')');
            final var body = parseStatement();
            return isOf ? new ForOfStatement(target, right, body, isAwait) : new ForInStatement(target, right, body);
        }

        private void validateForInOfTarget(JsNode left) {
            if (left instanceof VariableDeclaration declaration) {
                final var declarations = declaration.getDeclarations();
                if (declarations.size() != 1 || declarations.getFirst().getInit() != null) {
                    throw error();
                }
                return;
            }
            if (!(left instanceof Identifier) && !(left instanceof MemberExpression) && !(left instanceof ArrayPattern)
                    && !(left instanceof ObjectPattern)) {
                throw error();
            }
        }

        private ForStatement parseClassicForRest(JsNode init) {
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
            final var body = parseStatement();
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
            final var argument = parseExpression();
            consumeOptionalSemicolon();
            return new ThrowStatement(argument);
        }

        private SwitchStatement parseSwitch() {
            expectKeyword("switch");
            expectSeparator('(');
            final var discriminant = parseExpression();
            expectSeparator(')');
            expectSeparator('{');
            final var cases = new ArrayList<SwitchCase>();
            while (!isSeparator('}') && !atEnd()) {
                cases.add(parseSwitchCase());
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
                consequent.add(parseStatement());
            }
            return new SwitchCase(test, consequent);
        }

        private ReturnStatement parseReturn() {
            expectKeyword("return");
            Expression argument = null;
            if (!isSeparator(';') && !isSeparator('}') && !atEnd()) {
                argument = parseExpression();
            }
            consumeOptionalSemicolon();
            return new ReturnStatement(argument);
        }

        private BreakStatement parseBreak() {
            expectKeyword("break");
            final var label = current().getType() == JsType.IDENTIFIER ? parseIdentifier() : null;
            consumeOptionalSemicolon();
            return new BreakStatement(label);
        }

        private ContinueStatement parseContinue() {
            expectKeyword("continue");
            final var label = current().getType() == JsType.IDENTIFIER ? parseIdentifier() : null;
            consumeOptionalSemicolon();
            return new ContinueStatement(label);
        }

        private FunctionDeclaration parseFunctionDeclaration(boolean async) {
            expectKeyword("function");
            final var generator = matchOperator("*");
            final var name = parseIdentifier();
            final var params = parseParams();
            final var body = parseBlock();
            return new FunctionDeclaration(name, params, body, async, generator);
        }

        private ImportDeclaration parseImportDeclaration() {
            // `import` is a keyword, so import(...) dynamic imports and import.meta are not parsed here.
            expectKeyword("import");
            if (current().getType() == JsType.STRING) {
                final var source = parseModuleSource();
                final var attributes = parseImportAttributes();
                consumeOptionalSemicolon();
                return new ImportDeclaration(List.of(), source, attributes);
            }
            final var specifiers = new ArrayList<JsNode>();
            if (matchOperator("*")) {
                specifiers.add(parseImportNamespaceSpecifier());
            } else if (isSeparator('{')) {
                parseNamedImportSpecifiers(specifiers);
            } else {
                specifiers.add(new ImportDefaultSpecifier(parseIdentifier()));
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
            consumeOptionalSemicolon();
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
            return new ImportNamespaceSpecifier(parseIdentifier());
        }

        private void parseNamedImportSpecifiers(List<JsNode> specifiers) {
            expectSeparator('{');
            while (!isSeparator('}')) {
                final var imported = parseModuleExportName();
                final var local = matchContextualKeyword("as") ? parseIdentifier() : asBindingIdentifier(imported);
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
                consumeOptionalSemicolon();
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
            consumeOptionalSemicolon();
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
            consumeOptionalSemicolon();
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

        private boolean isContextualKeyword(String word) {
            final var t = current();
            return t.getType() == JsType.IDENTIFIER && ((JsIdentifier) t).getValue().equals(word);
        }

        private boolean matchContextualKeyword(String word) {
            if (isContextualKeyword(word)) {
                advance();
                return true;
            }
            return false;
        }

        private void expectContextualKeyword(String word) {
            if (!matchContextualKeyword(word)) {
                throw error();
            }
        }

        private ExpressionStatement parseExpressionStatement() {
            final var expr = parseExpression();
            consumeOptionalSemicolon();
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
            return params;
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
            return parseIdentifier();
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
            if (matchOperator("=")) {
                return new Property(key, new AssignmentPattern(key, parseAssignment()), false, true);
            }
            return new Property(key, key, false, true);
        }

        private JsNode resolveAssignmentTarget(Expression left, String op) {
            if (left instanceof Identifier || left instanceof MemberExpression) {
                return left;
            }
            if ("=".equals(op) && (left instanceof ArrayExpression || left instanceof ObjectExpression)) {
                return toAssignmentPattern(left);
            }
            throw error();
        }

        // Cover grammar: an array/object expression parsed on an assignment LHS is reinterpreted into
        // the equivalent binding pattern once the `=` proves the intent.
        private JsNode toAssignmentPattern(Expression expr) {
            if (expr instanceof Identifier || expr instanceof MemberExpression) {
                return expr;
            }
            if (expr instanceof ArrayExpression array) {
                final var elements = new ArrayList<JsNode>();
                for (final var element : array.getElements()) {
                    elements.add(toPatternElement(element));
                }
                return new ArrayPattern(elements);
            }
            if (expr instanceof ObjectExpression object) {
                final var properties = new ArrayList<JsNode>();
                for (final var property : object.getProperties()) {
                    properties.add(toPatternProperty(property));
                }
                return new ObjectPattern(properties);
            }
            throw error();
        }

        private JsNode toPatternElement(Expression element) {
            if (element == null) {
                return null;
            }
            if (element instanceof SpreadElement spread) {
                return new RestElement(toAssignmentPattern(spread.getArgument()));
            }
            return toPatternDefault(element);
        }

        private JsNode toPatternProperty(JsNode property) {
            if (property instanceof SpreadElement spread) {
                return new RestElement(toAssignmentPattern(spread.getArgument()));
            }
            final var prop = (Property) property;
            final var value = prop.getValue();
            if (!(value instanceof Expression valueExpr)) {
                throw error();
            }
            return new Property(prop.getKey(), toPatternDefault(valueExpr), prop.isComputed(), prop.isShorthand());
        }

        private JsNode toPatternDefault(Expression expr) {
            if (expr instanceof AssignmentExpression assignment && "=".equals(assignment.getOperator())) {
                return new AssignmentPattern(toBindingTarget(assignment.getTarget()), assignment.getValue());
            }
            return toAssignmentPattern(expr);
        }

        // An assignment LHS may already be a reinterpreted pattern (e.g. `{a: [x] = d}`); only a raw
        // array/object expression still needs converting.
        private JsNode toBindingTarget(JsNode node) {
            if (node instanceof Expression expr) {
                return toAssignmentPattern(expr);
            }
            return node;
        }

        private Expression parseExpression() {
            return parseAssignment();
        }

        private Expression parseAssignment() {
            if (isKeyword("yield")) {
                return parseYield();
            }
            final var left = parseConditional();
            if (current().getType() == JsType.OPERATOR) {
                final var op = ((JsOperator) current()).getValue();
                if (ASSIGNMENT_OPERATORS.contains(op)) {
                    final var target = resolveAssignmentTarget(left, op);
                    advance();
                    return new AssignmentExpression(op, target, parseAssignment());
                }
            }
            return left;
        }

        private Expression parseYield() {
            expectKeyword("yield");
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
            while (op != null && BINARY_PRECEDENCE.get(op) >= minPrec) {
                final int prec = BINARY_PRECEDENCE.get(op);
                advance();
                final int nextMinPrec = "**".equals(op) ? prec : prec + 1;
                final var right = parseBinary(nextMinPrec);
                left = LOGICAL_OPERATORS.contains(op)
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
                return BINARY_PRECEDENCE.containsKey(op) ? op : null;
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
                if (PREFIX_UNARY_OPERATORS.contains(op)) {
                    advance();
                    return new UnaryExpression(op, parseUnary(), true);
                }
                if ("++".equals(op) || "--".equals(op)) {
                    advance();
                    return new UpdateExpression(op, parseUnary(), true);
                }
            }
            if (t.getType() == JsType.KEYWORD) {
                final var kw = ((JsKeyword) t).getValue();
                if ("typeof".equals(kw) || "void".equals(kw) || "delete".equals(kw)) {
                    advance();
                    return new UnaryExpression(kw, parseUnary(), true);
                }
                if ("await".equals(kw)) {
                    advance();
                    return new AwaitExpression(parseUnary());
                }
            }
            return parsePostfix();
        }

        private Expression parsePostfix() {
            final var expr = parseCallMember();
            if (isOperator("++") || isOperator("--")) {
                final var op = ((JsOperator) advance()).getValue();
                return new UpdateExpression(op, expr, false);
            }
            return expr;
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
                    return new RegexLiteral(((JsRegex) t).getPattern(), ((JsRegex) t).getFlags());
                }
                case TEMPLATE_STRING -> {
                    advance();
                    return parseTemplate((JsTemplateString) t);
                }
                case IDENTIFIER -> {
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
            if (peek().getType() == JsType.OPERATOR && "=>".equals(((JsOperator) peek()).getValue())) {
                advance();
                advance();
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
                    yield new SuperExpression();
                }
                default -> throw error();
            };
        }

        private Expression parseAsyncPrimary() {
            expectKeyword("async");
            if (isKeyword("function")) {
                return parseFunctionExpression(true);
            }
            if (current().getType() == JsType.IDENTIFIER && peek().getType() == JsType.OPERATOR
                    && "=>".equals(((JsOperator) peek()).getValue())) {
                final var param = parseIdentifier();
                expectOperator("=>");
                return parseArrowBody(List.of(param), true);
            }
            if (isSeparator('(') && matchingParenFollowedByArrow()) {
                final var params = parseParams();
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
                name = parseIdentifier();
            }
            final var params = parseParams();
            final var body = parseBlock();
            return new FunctionExpression(name, params, body, async, generator);
        }

        private ClassDeclaration parseClassDeclaration() {
            expectKeyword("class");
            final var id = parseIdentifier();
            final var superClass = parseClassHeritage();
            return new ClassDeclaration(id, superClass, parseClassBody());
        }

        private ClassExpression parseClassExpression() {
            expectKeyword("class");
            Identifier id = null;
            if (current().getType() == JsType.IDENTIFIER) {
                id = parseIdentifier();
            }
            final var superClass = parseClassHeritage();
            return new ClassExpression(id, superClass, parseClassBody());
        }

        private Expression parseClassHeritage() {
            return matchKeyword("extends") ? parseCallMember() : null;
        }

        private ClassBody parseClassBody() {
            expectSeparator('{');
            final var members = new ArrayList<JsNode>();
            while (!isSeparator('}') && !atEnd()) {
                if (matchSeparator(';')) {
                    continue;
                }
                members.add(parseClassMember());
            }
            expectSeparator('}');
            return new ClassBody(members);
        }

        private JsNode parseClassMember() {
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
                final var value = new FunctionExpression(null, parseParams(), parseBlock(), async, generator);
                final var resolvedKind = resolveMethodKind(kind, memberKey, isStatic, async, generator);
                return new MethodDefinition(memberKey.key(), value, resolvedKind, isStatic, memberKey.computed());
            }
            if (!"method".equals(kind) || async || generator) {
                throw error();
            }
            Expression value = null;
            if (matchOperator("=")) {
                value = parseAssignment();
            }
            consumeOptionalSemicolon();
            return new FieldDefinition(memberKey.key(), value, isStatic, memberKey.computed());
        }

        private StaticBlock parseStaticBlock() {
            expectSeparator('{');
            final var body = new ArrayList<Statement>();
            while (!isSeparator('}') && !atEnd()) {
                body.add(parseStatement());
            }
            expectSeparator('}');
            return new StaticBlock(body);
        }

        private String resolveMethodKind(String kind, MemberKey memberKey, boolean isStatic, boolean async,
                boolean generator) {
            if (!"method".equals(kind)) {
                return kind;
            }
            if (!async && !generator && !isStatic && !memberKey.computed() && memberKey.key() instanceof Identifier id
                    && "constructor".equals(id.getName())) {
                return "constructor";
            }
            return "method";
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
            if (t.getType() != JsType.IDENTIFIER || !((JsIdentifier) t).getValue().equals(name)) {
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
            if (isSeparator('{')) {
                return new ArrowFunctionExpression(params, parseBlock(), false, async);
            }
            return new ArrowFunctionExpression(params, parseAssignment(), true, async);
        }

        private ArrayExpression parseArray() {
            expectSeparator('[');
            final var elements = new ArrayList<Expression>();
            while (!isSeparator(']')) {
                if (matchSeparator(',')) {
                    elements.add(null);
                    continue;
                }
                elements.add(parseSpreadableExpression());
                if (!isSeparator(']')) {
                    expectSeparator(',');
                }
            }
            expectSeparator(']');
            return new ArrayExpression(elements);
        }

        private ObjectExpression parseObject() {
            expectSeparator('{');
            final var properties = new ArrayList<JsNode>();
            if (!isSeparator('}')) {
                do {
                    if (isSeparator('}')) {
                        break;
                    }
                    properties.add(parseObjectMember());
                } while (matchSeparator(','));
            }
            expectSeparator('}');
            return new ObjectExpression(properties);
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
            final var key = parsePropertyKey();
            if (isSeparator('(')) {
                final var value = new FunctionExpression(null, parseParams(), parseBlock(), async, generator);
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
            return t.getType() == JsType.IDENTIFIER && ((JsIdentifier) t).getValue().equals(name);
        }

        private TemplateLiteral parseTemplate(JsTemplateString template) {
            final var expressions = new ArrayList<Expression>();
            for (final var expressionTokens : template.getExpressions()) {
                expressions.add(new State(expressionTokens, null).parseTemplateExpression());
            }
            return new TemplateLiteral(template.getQuasis(), template.getRawQuasis(), expressions);
        }

        private Expression parseTemplateExpression() {
            final var expr = parseExpression();
            if (!atEnd()) {
                throw error();
            }
            return expr;
        }

        private JsBaseElement current() {
            return tokens.get(pos);
        }

        private JsBaseElement peek() {
            return peekAt(1);
        }

        private JsBaseElement peekAt(int offset) {
            return tokens.get(Math.min(pos + offset, tokens.size() - 1));
        }

        private JsBaseElement advance() {
            final var t = current();
            if (t.getType() != JsType.EOF) {
                pos++;
            }
            return t;
        }

        private boolean atEnd() {
            return current().getType() == JsType.EOF;
        }

        private void consumeOptionalSemicolon() {
            matchSeparator(';');
        }

        // The for-header left-hand side is parsed under the no-in production: `in` is not a
        // binary operator there, so `for (a in b)` reads `in` as the loop keyword. A bracketed
        // sub-expression re-enters the [+In] grammar (innermost context wins via the stack),
        // so the `in` in `for ((a in b); ;)` is still a binary operator.
        private <T> T withNoIn(Supplier<T> parse) {
            return withInContext(Boolean.TRUE, parse);
        }

        private <T> T withInAllowed(Supplier<T> parse) {
            return withInContext(Boolean.FALSE, parse);
        }

        private <T> T withInContext(Boolean suppressIn, Supplier<T> parse) {
            noInStack.push(suppressIn);
            try {
                return parse.get();
            } finally {
                noInStack.pop();
            }
        }

        private boolean isSeparator(char c) {
            final var t = current();
            return t.getType() == JsType.SEPARATOR && ((JsSeparator) t).getValue() == c;
        }

        private boolean matchSeparator(char c) {
            if (isSeparator(c)) {
                advance();
                return true;
            }
            return false;
        }

        private void expectSeparator(char c) {
            if (!matchSeparator(c)) {
                throw error();
            }
        }

        private boolean isOperator(String op) {
            final var t = current();
            return t.getType() == JsType.OPERATOR && ((JsOperator) t).getValue().equals(op);
        }

        private boolean matchOperator(String op) {
            if (isOperator(op)) {
                advance();
                return true;
            }
            return false;
        }

        private void expectOperator(String op) {
            if (!matchOperator(op)) {
                throw error();
            }
        }

        private boolean isKeyword(String kw) {
            final var t = current();
            return t.getType() == JsType.KEYWORD && ((JsKeyword) t).getValue().equals(kw);
        }

        private boolean matchKeyword(String kw) {
            if (isKeyword(kw)) {
                advance();
                return true;
            }
            return false;
        }

        private void expectKeyword(String kw) {
            if (!matchKeyword(kw)) {
                throw error();
            }
        }

        private RuntimeException error() {
            final var position = positions != null ? positions.get(pos) : null;
            if (atEnd()) {
                return position != null
                        ? new UnexpectedEndOfInputException(position.getLine(), position.getColumn())
                        : new UnexpectedEndOfInputException();
            }
            return position != null
                    ? new UnexpectedTokenException(describe(current()), position.getLine(), position.getColumn())
                    : new UnexpectedTokenException(describe(current()), pos);
        }

        private String describe(JsBaseElement t) {
            return switch (t.getType()) {
                case KEYWORD -> ((JsKeyword) t).getValue();
                case IDENTIFIER -> ((JsIdentifier) t).getValue();
                case PRIVATE_IDENTIFIER -> "#" + ((JsPrivateIdentifier) t).getValue();
                case NUMBER -> String.valueOf(((JsNumber) t).getValue());
                case BIGINT -> ((JsBigInt) t).getValue() + "n";
                case STRING -> '"' + ((JsString) t).getValue() + '"';
                case BOOLEAN -> String.valueOf(((JsBoolean) t).getValue());
                case NULL -> "null";
                case UNDEFINED -> "undefined";
                case OPERATOR -> ((JsOperator) t).getValue();
                case SEPARATOR -> String.valueOf(((JsSeparator) t).getValue());
                case REGEX -> "/" + ((JsRegex) t).getPattern() + "/" + ((JsRegex) t).getFlags();
                case TEMPLATE_STRING -> "template literal";
                case EOF -> "<eof>";
            };
        }
    }
}

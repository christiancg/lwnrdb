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
import org.techhouse.simplejs.elements.JsBoolean;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsRegex;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
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
import org.techhouse.simplejs.nodes.EmptyStatement;
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
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.NullLiteral;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RegexLiteral;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.SwitchCase;
import org.techhouse.simplejs.nodes.SwitchStatement;
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
                        return parseFunctionDeclaration();
                    }
                    case "class" -> {
                        return parseClassDeclaration();
                    }
                    default -> {
                    }
                }
            }
            return parseExpressionStatement();
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
                final var id = parseIdentifier();
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

        private Statement parseFor() {
            expectKeyword("for");
            expectSeparator('(');
            if (isSeparator(';')) {
                return parseClassicForRest(null);
            }
            final var init = parseForHeaderLeft();
            if (isKeyword("in") || isKeyword("of")) {
                return parseForInOf(init);
            }
            return parseClassicForRest(init);
        }

        private JsNode parseForHeaderLeft() {
            if (isKeyword("var") || isKeyword("let") || isKeyword("const")) {
                return parseForVariableDeclaration();
            }
            return withNoIn(this::parseExpression);
        }

        private VariableDeclaration parseForVariableDeclaration() {
            final var kind = ((JsKeyword) advance()).getValue();
            return withNoIn(() -> {
                final var declarations = new ArrayList<VariableDeclarator>();
                do {
                    final var id = parseIdentifier();
                    Expression init = null;
                    if (matchOperator("=")) {
                        init = parseAssignment();
                    }
                    declarations.add(new VariableDeclarator(id, init));
                } while (matchSeparator(','));
                return new VariableDeclaration(kind, declarations);
            });
        }

        private Statement parseForInOf(JsNode left) {
            validateForInOfTarget(left);
            final var isOf = "of".equals(((JsKeyword) current()).getValue());
            advance();
            final var right = isOf ? parseAssignment() : parseExpression();
            expectSeparator(')');
            final var body = parseStatement();
            return isOf ? new ForOfStatement(left, right, body) : new ForInStatement(left, right, body);
        }

        private void validateForInOfTarget(JsNode left) {
            if (left instanceof VariableDeclaration declaration) {
                final var declarations = declaration.getDeclarations();
                if (declarations.size() != 1 || declarations.getFirst().getInit() != null) {
                    throw error();
                }
                return;
            }
            if (!(left instanceof Identifier) && !(left instanceof MemberExpression)) {
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
            Identifier param = null;
            if (matchSeparator('(')) {
                param = parseIdentifier();
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
            consumeOptionalSemicolon();
            return new BreakStatement();
        }

        private ContinueStatement parseContinue() {
            expectKeyword("continue");
            consumeOptionalSemicolon();
            return new ContinueStatement();
        }

        private FunctionDeclaration parseFunctionDeclaration() {
            expectKeyword("function");
            final var name = parseIdentifier();
            final var params = parseParams();
            final var body = parseBlock();
            return new FunctionDeclaration(name, params, body);
        }

        private ExpressionStatement parseExpressionStatement() {
            final var expr = parseExpression();
            consumeOptionalSemicolon();
            return new ExpressionStatement(expr);
        }

        private List<Identifier> parseParams() {
            expectSeparator('(');
            final var params = new ArrayList<Identifier>();
            if (!isSeparator(')')) {
                do {
                    if (isSeparator(')')) {
                        break;
                    }
                    params.add(parseIdentifier());
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

        private Expression parseExpression() {
            return parseAssignment();
        }

        private Expression parseAssignment() {
            final var left = parseConditional();
            if (current().getType() == JsType.OPERATOR) {
                final var op = ((JsOperator) current()).getValue();
                if (ASSIGNMENT_OPERATORS.contains(op)) {
                    if (!(left instanceof Identifier) && !(left instanceof MemberExpression)) {
                        throw error();
                    }
                    advance();
                    return new AssignmentExpression(op, left, parseAssignment());
                }
            }
            return left;
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
                } else {
                    advancing = false;
                }
            }
            return expr;
        }

        private Expression parseOptionalTail(Expression object) {
            if (isSeparator('(')) {
                return new CallExpression(object, parseArguments());
            }
            if (isSeparator('[')) {
                advance();
                final var property = withInAllowed(this::parseExpression);
                expectSeparator(']');
                return new MemberExpression(object, property, true, true);
            }
            return new MemberExpression(object, parseMemberProperty(), false, true);
        }

        private Identifier parseMemberProperty() {
            final var t = current();
            if (t.getType() == JsType.IDENTIFIER) {
                advance();
                return new Identifier(((JsIdentifier) t).getValue());
            }
            if (t.getType() == JsType.KEYWORD) {
                advance();
                return new Identifier(((JsKeyword) t).getValue());
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
                    arguments.add(withInAllowed(this::parseAssignment));
                } while (matchSeparator(','));
            }
            expectSeparator(')');
            return arguments;
        }

        private Expression parsePrimary() {
            final var t = current();
            switch (t.getType()) {
                case NUMBER -> {
                    advance();
                    return new NumberLiteral(((JsNumber) t).getValue());
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
                return parseArrowBody(List.of(new Identifier(t.getValue())));
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
                case "function" -> parseFunctionExpression();
                case "class" -> parseClassExpression();
                case "super" -> {
                    advance();
                    yield new SuperExpression();
                }
                default -> throw error();
            };
        }

        private FunctionExpression parseFunctionExpression() {
            expectKeyword("function");
            Identifier name = null;
            if (current().getType() == JsType.IDENTIFIER) {
                name = parseIdentifier();
            }
            final var params = parseParams();
            final var body = parseBlock();
            return new FunctionExpression(name, params, body);
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
            var kind = "method";
            if (matchContextualModifier("get")) {
                kind = "get";
            } else if (matchContextualModifier("set")) {
                kind = "set";
            }
            final var memberKey = parseClassMemberKey();
            if (isSeparator('(')) {
                final var value = new FunctionExpression(null, parseParams(), parseBlock());
                final var resolvedKind = resolveMethodKind(kind, memberKey, isStatic);
                return new MethodDefinition(memberKey.key(), value, resolvedKind, isStatic, memberKey.computed());
            }
            if (!"method".equals(kind)) {
                throw error();
            }
            Expression value = null;
            if (matchOperator("=")) {
                value = parseAssignment();
            }
            consumeOptionalSemicolon();
            return new FieldDefinition(memberKey.key(), value, isStatic, memberKey.computed());
        }

        private String resolveMethodKind(String kind, MemberKey memberKey, boolean isStatic) {
            if (!"method".equals(kind)) {
                return kind;
            }
            if (!isStatic && !memberKey.computed() && memberKey.key() instanceof Identifier id
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
                return parseArrowBody(params);
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

        private ArrowFunctionExpression parseArrowBody(List<Identifier> params) {
            if (isSeparator('{')) {
                return new ArrowFunctionExpression(params, parseBlock(), false);
            }
            return new ArrowFunctionExpression(params, parseAssignment(), true);
        }

        private ArrayExpression parseArray() {
            expectSeparator('[');
            final var elements = new ArrayList<Expression>();
            if (!isSeparator(']')) {
                do {
                    if (isSeparator(']')) {
                        break;
                    }
                    elements.add(withInAllowed(this::parseAssignment));
                } while (matchSeparator(','));
            }
            expectSeparator(']');
            return new ArrayExpression(elements);
        }

        private ObjectExpression parseObject() {
            expectSeparator('{');
            final var properties = new ArrayList<Property>();
            if (!isSeparator('}')) {
                do {
                    if (isSeparator('}')) {
                        break;
                    }
                    properties.add(withInAllowed(this::parseProperty));
                } while (matchSeparator(','));
            }
            expectSeparator('}');
            return new ObjectExpression(properties);
        }

        private Property parseProperty() {
            if (isSeparator('[')) {
                advance();
                final var key = parseAssignment();
                expectSeparator(']');
                expectOperator(":");
                return new Property(key, parseAssignment(), true, false);
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
                return new Property(key, parseAssignment(), false, false);
            }
            if (t.getType() == JsType.IDENTIFIER) {
                return new Property(key, key, false, true);
            }
            throw error();
        }

        private TemplateLiteral parseTemplate(JsTemplateString template) {
            final var expressions = new ArrayList<Expression>();
            for (final var expressionTokens : template.getExpressions()) {
                expressions.add(new State(expressionTokens, null).parseTemplateExpression());
            }
            return new TemplateLiteral(template.getQuasis(), expressions);
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
            return tokens.get(Math.min(pos + 1, tokens.size() - 1));
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
                case NUMBER -> String.valueOf(((JsNumber) t).getValue());
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

# SimpleJS

SimpleJS is a small, dependency-free JavaScript engine embedded in LWNRDB. Its
purpose is to let user-supplied scripts run inside the database — the same
zero-runtime-dependency constraint that governs EJson and the JSON Schema
validator applies here: everything is hand-rolled under
`org.techhouse.simplejs`, with no external parser or JS runtime.

The engine is built as a classic three-stage pipeline:

```
source String
  → Lexer.lex(source)      → List<JsBaseElement>   (tokens)
  → Parser.parse(tokens)   → Program               (AST)
  → Interpreter.run(ast)   → result                (evaluation)
```

Only the **lexer** exists today. This document describes the full engine and the
**phased plan** for building the parser and interpreter on top of it.

> **Status legend:** ✅ implemented · 🚧 planned (this document) · ⬜ deferred to a
> later phase.

## Package layout

| Package | Responsibility |
|---|---|
| `simplejs/elements/` | ✅ Token types produced by the lexer. `JsBaseElement` is the abstract base with a `JsType` enum resolved by a centralized `internalGetType` switch; each concrete token (`JsKeyword`, `JsIdentifier`, `JsNumber`, `JsString`, `JsBoolean`, `JsNull`, `JsUndefined`, `JsOperator`, `JsSeparator`, `JsRegex`, `JsTemplateString`, `JsEOF`) is a small immutable class with `getX()` getters. Singletons (`JsNull`/`JsUndefined`/`JsEOF`) use `getInstance()`. `SourcePosition` (offset/length/line/column) is a token-location value held **parallel** to the token stream rather than on the tokens, so the singletons keep their identity. |
| `simplejs/nodes/` | 🚧 AST node types produced by the parser. Mirrors the `elements/` convention exactly: an abstract `JsNode` base with a `NodeType` enum resolved by a centralized `internalGetType` switch, plus `Expression`/`Statement` marker abstract subclasses for parser type-safety. |
| `simplejs/internal/` | `Lexer` (✅) and `Parser` (🚧), later `Interpreter` (⬜). Each is a `final` class with a public `static` entry point wrapping encapsulated state. `Lexer.lexWithPositions` returns a `LexResult(source, tokens, positions)`; `Lexer.lex` delegates to it and returns just the tokens. `Parser.parse` has a `LexResult` overload (position-aware errors) alongside the token-list overload (index-based errors). |
| `simplejs/exceptions/` | Dedicated `RuntimeException` subclasses. Lexer errors (✅): `UnexpectedCharacterException`, `UnterminatedCommentException`, `UnterminatedRegexException`, `UnterminatedStringException`, `UnterminatedTemplateException`. Parser errors (🚧): `UnexpectedTokenException`, `UnexpectedEndOfInputException` (each has both a token-index/plain constructor and a line/column constructor). |

## The lexer (implemented)

`Lexer.lex(String)` scans source into a `List<JsBaseElement>` terminated by a
`JsEOF` singleton. It handles line/block comments, single/double-quoted strings
with full escape sequences (`\n`, `\xNN`, `\uNNNN`, `\u{...}`), numeric literals
(decimal, `0x`/`0o`/`0b`, exponents), identifiers/keywords, the multi-character
operator set (longest-match first), separators, template literals (with nested
`${...}` interpolations lexed recursively into sub-token-lists), and the
regex-vs-division ambiguity via the standard "can the previous token end an
expression?" heuristic (`startsRegex`).

The recognized keyword set is broad — `if do while for in of switch case default
var let const break continue return try catch finally throw async await yield
function import export this constructor new class else typeof instanceof void
delete extends super` — which defines the grammar the parser and interpreter grow
toward across the phases below.

> **Lexer constraint that shapes the parser:** the lexer **discards newlines**
> (all whitespace is skipped as token boundaries). This makes newline-sensitive
> Automatic Semicolon Insertion (ASI) impossible from the token stream, so the
> parser uses a pragmatic termination rule instead (see Phase 1).

> **Source positions.** The lexer records each token's location — 0-based
> `offset` and `length`, plus the 1-based `line` and `column` of its start
> (`elements/SourcePosition`). Because `JsNull`/`JsUndefined`/`JsEOF` are shared
> singletons, positions cannot live on the token objects; instead
> `Lexer.lexWithPositions(source)` returns a `LexResult(source, tokens,
> positions)` whose `positions` list runs **parallel to `tokens`** (one entry per
> token, EOF included), keyed by the same index the parser's cursor uses.
> `Lexer.lex(source)` remains and simply returns `lexWithPositions(source)
> .tokens()`. Parsing via `Parser.parse(LexResult)` surfaces the line and column
> of the offending token in `UnexpectedTokenException` /
> `UnexpectedEndOfInputException`; the legacy `Parser.parse(List<JsBaseElement>)`
> overload has no positions and falls back to reporting the token and its index.
> Line/column are derived from `\n` offsets in the source, so they are accurate
> even though newlines are not themselves emitted as tokens. Nested template
> interpolations are still lexed position-less, so an error inside a `${...}`
> uses the index-based fallback.

## The AST (`nodes/`)

Every node extends `JsNode`, which mirrors `JsBaseElement`: a `NodeType` enum and
a private `internalGetType` switch with one arm per concrete leaf node. Two empty
abstract subclasses — `Expression` and `Statement` — let parser methods return
`Expression`/`Statement` for type safety (a light, deliberate divergence from the
flat `elements/` hierarchy). `Program`, `VariableDeclarator`, and `Property`
extend `JsNode` directly. Concrete nodes are small immutable classes with `final`
fields and getters, exactly like the token classes.

```java
import org.techhouse.simplejs.nodes.IfStatement;

public abstract class JsNode {
  public enum NodeType {PROGRAM, BINARY_EXPRESSION, IF_STATEMENT, /* ... */}

  public NodeType getType() {
    return internalGetType(this);
  }

  private static NodeType internalGetType(Object o) {
    return switch (o) {
      case Program ignored -> NodeType.PROGRAM;
      case IfStatement ignored -> NodeType.IF_STATEMENT;
      // ... one arm per concrete leaf node ...
      default -> throw new IllegalStateException("Unexpected value: " + o);
    };
  }
}
```

## The parser (`Parser.parse`)

The parser is **recursive descent** with a **Pratt / precedence-climbing** core
for expressions. It mirrors the `Lexer` convention:

```java
public final class Parser {
    private final List<JsBaseElement> tokens;
    private int pos;
    private Parser(List<JsBaseElement> tokens) { this.tokens = tokens; }
    public static Program parse(List<JsBaseElement> tokens) {
        return new Parser(tokens).parseProgram();
    }
}
```

Cursor helpers (`current`, `peek`, `advance`, `atEnd`), matchers/expecters
(`matchSeparator`/`expectSeparator`, `matchOperator`/`expectOperator`,
`matchKeyword`/`expectKeyword`, `isKeyword`), and an `error()` that throws the
parser exceptions form the substrate. Statement parsing dispatches on the current
token; expression parsing climbs a precedence ladder.

### Expression precedence (loosest → tightest)

```
assignment  ( = += -= *= /= %= **= <<= >>= >>>= &= |= ^= &&= ||= ??= )  right-assoc
conditional ( ?: )
??  →  ||  →  &&  →  |  →  ^  →  &
==/!=/===/!==   →   < <= > >= instanceof in
<< >> >>>   →   + -   →   * / %   →   ** (right-assoc)
unary       ( ! ~ + - typeof void delete, prefix ++ -- )
postfix     ( ++ -- )
call/member/new   ( . ?. [] () )
primary     ( literals, identifier, this, (), [], {}, function, arrow, template )
```

A `static Map<String,Integer>` holds binary precedences; a small set marks
right-associative operators (`**`). `instanceof` and `in` arrive as `JsKeyword`
tokens (not `JsOperator`), so binary-operator detection checks keyword values too.

### Parser design notes

- **Arrow detection.** An `Identifier` followed by `=>` is a single-param arrow. A
  `(` is treated as arrow params iff a forward scan to the matching `)` is followed
  by `=>` (`matchingParenFollowedByArrow()`); otherwise it is a grouping
  expression. The full token list makes this lookahead cheap.
- **Template literals.** `JsTemplateString` already carries each `${...}`
  interpolation as its own `List<JsBaseElement>` (each terminated by `JsEOF`), so
  `parseTemplate` runs a nested `Parser` per interpolation, parsing one expression
  and expecting EOF.
- **Pragmatic ASI.** Because the lexer drops newlines, a statement ends at `;`, at
  `}`, or at EOF, and a trailing `;` is optional (`consumeOptionalSemicolon`).
  Newline-sensitive ASI is a documented Phase-1 limitation.

## Implementation phases

The grammar the lexer implies is large, so the parser (and later the interpreter)
are built in phases. Each phase is independently reviewable, keeps the build green
(`mvn verify`, JaCoCo ≥ 95% instruction coverage, plus Spotless/Checkstyle/PMD/
SpotBugs), and ships with unit tests (mirroring the package under
`src/test/.../unit/simplejs/`) and lexer-through-parser "program" tests
(mirroring `LexerProgramTest`).

### Phase 1 — expressions + core statements 🚧

The foundational grammar: everything needed to parse straight-line code and
first-class functions.

**Expressions:** number/string/boolean/null/undefined/regex/template literals,
identifiers, `this`, array and object literals (incl. shorthand and computed
keys), unary/update/binary/logical/assignment/conditional expressions, calls,
member access (`.`, `[]`, `?.`), `new`, function expressions, and arrow functions
(expression and block bodies).

**Statements:** `var`/`let`/`const` declarations, block statements, `if`/`else`,
`while`, C-style `for (init; test; update)`, `return`/`break`/`continue`,
expression statements, function declarations, and empty statements.

**Nodes introduced:** `Program`, `Expression`, `Statement`, `VariableDeclaration`,
`VariableDeclarator`, `BlockStatement`, `IfStatement`, `WhileStatement`,
`ForStatement`, `ReturnStatement`, `BreakStatement`, `ContinueStatement`,
`ExpressionStatement`, `FunctionDeclaration`, `EmptyStatement`, `NumberLiteral`,
`StringLiteral`, `BooleanLiteral`, `NullLiteral`, `UndefinedLiteral`,
`RegexLiteral`, `TemplateLiteral`, `Identifier`, `ThisExpression`,
`ArrayExpression`, `ObjectExpression`, `Property`, `UnaryExpression`,
`UpdateExpression`, `BinaryExpression`, `LogicalExpression`,
`AssignmentExpression`, `ConditionalExpression`, `CallExpression`,
`MemberExpression`, `NewExpression`, `FunctionExpression`,
`ArrowFunctionExpression`.

**Known Phase-1 gaps:** no newline-based ASI (lexer constraint). The `for (x in
y)` header misparse is closed in Phase 2 (the no-in production); newline-based ASI
remains a lexer constraint.

### Phase 2 — remaining control flow ✅

- **`for-in` / `for-of`** — `in`/`of` after the loop binding in the `for` header is
  detected and produces `ForInStatement` / `ForOfStatement`. The header's
  left-hand side is parsed under the **"no-in" grammar production** (a `noIn` flag
  on the parser suppresses `in`-as-operator, cleared again inside any bracketed
  sub-expression via `withInAllowed`) so `for (a in b)` disambiguates cleanly. The
  target is validated: a declaration must be a single declarator with no
  initializer, an expression target must be an `Identifier` or `MemberExpression`.
  This closes the Phase-1 `for`-header gap.
- **`try` / `catch` / `finally` / `throw`** — `TryStatement` (block + optional
  catch clause with optional binding + optional finalizer; at least one of catch /
  finally is required), `ThrowStatement`, `CatchClause`.
- **`switch` / `case` / `default`** — `SwitchStatement`, `SwitchCase` (a `null`
  test marks the `default` clause).

### Phase 3 — classes ⬜

`class` declarations and expressions: `ClassDeclaration`, `ClassExpression`,
`ClassBody`, `MethodDefinition` (incl. `constructor`, getters/setters, `static`
members), field definitions, `extends`, and `super` calls/member access.

### Phase 4 — async & generators ⬜

- **`async` / `await`** — `async` function declarations/expressions/arrows and the
  `await` unary; `AwaitExpression` plus an `async` flag on the function nodes.
- **Generators** — `function*` and `yield`/`yield*`; `YieldExpression` plus a
  `generator` flag on the function nodes.

### Phase 5 — modules & patterns ⬜

- **Modules** — `import`/`export` in their several forms: `ImportDeclaration`,
  `ExportNamedDeclaration`, `ExportDefaultDeclaration`, `ExportAllDeclaration`,
  and the specifier nodes. (Module *resolution* semantics are an interpreter
  concern; this phase covers parsing only.)
- **Destructuring** — array/object binding and assignment patterns
  (`ArrayPattern`, `ObjectPattern`, `AssignmentPattern`) wherever a binding target
  appears (declarations, params, assignment LHS).
- **Spread / rest** — `SpreadElement` in array/call/object literals and
  `RestElement` in params and patterns.

### Phase 6+ — the interpreter ⬜

A tree-walking `Interpreter` over the AST: lexical scopes/environments, closures,
prototype-free object/array/function values, the standard operator semantics, and
control-flow (via internal completion signals for `return`/`break`/`continue`/
`throw`). The evaluation surface and its integration with the database (how a
script is invoked, what host values and built-ins it sees, and its sandboxing/
resource limits) will be specified when this phase is planned. This is where
SimpleJS becomes useful to the database rather than a standalone parser.

## Testing conventions

Tests use **JUnit 5**, live under `src/test/java/org/techhouse/unit/simplejs/`
mirroring the main package structure, and follow the existing `LexerTest` style
(`assertInstanceOf`/`assertEquals`, one-line intent comments). Each phase adds:

- **Node tests** (`nodes/JsNodeTest`) — assert `getType()` for every concrete node
  introduced, mirroring `JsBaseElementTest` and driving the `internalGetType`
  switch.
- **Parser unit tests** (`internal/ParserTest`) — assert AST shape per construct,
  plus negative tests (`assertThrows` for `UnexpectedTokenException` /
  `UnexpectedEndOfInputException`) and boundary cases (empty program, empty
  argument/element/param lists, deep precedence nesting).
- **Program tests** (`internal/ParserProgramTest`) — full `source → Lexer.lex →
  Parser.parse` on realistic snippets, the lexer-through-parser end-to-end
  coverage.

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

### Phase 3 — classes ✅

- **`class` declarations and expressions** — `ClassDeclaration` (statement, named)
  and `ClassExpression` (primary, name optional), each with an optional `extends`
  heritage (parsed as a left-hand-side expression via the call/member path) and a
  `ClassBody`.
- **`ClassBody`** holds a heterogeneous `List<JsNode>` of members; stray `;`
  between members are skipped.
- **`MethodDefinition`** (`key`, `value` `FunctionExpression`, `kind`, `isStatic`,
  `computed`) covers plain methods, the `constructor` (`kind == "constructor"`,
  detected only for a non-static, non-computed `constructor`-named key), and
  getters/setters (`kind == "get"`/`"set"`). **`FieldDefinition`** (`key`,
  optional `value`, `isStatic`, `computed`) covers class fields.
- **Contextual modifiers.** `static`, `get`, and `set` are **not** keywords — they
  lex as identifiers, so `matchContextualModifier` treats them as modifiers only
  when the following token begins a member key; when followed by `(`, `=`, `;` or
  `}` the word is the member name itself (`static() {}`, `get = 1`).
- **`super`** parses to a `SuperExpression` primary; the existing call/member tail
  produces `super(...)` calls and `super.x` / `super[k]` access with no extra
  handling. `super`/`this` context validity is left to the interpreter.

### Phase 4 — async & generators ✅

- **`async` / `await`** — `async` function declarations (`async function f(){}`),
  function expressions (`async function(){}`) and arrows (`async x => x`,
  `async (a,b) => {}`); the `await` unary produces `AwaitExpression` (parsed in
  `parseUnary`, so it binds tighter than binary operators). Function nodes carry an
  `async` flag (`FunctionDeclaration`/`FunctionExpression`/`ArrowFunctionExpression`
  gain `isAsync()`).
- **Generators** — `function*` declarations/expressions and `yield`/`yield*`.
  `YieldExpression` (`argument` nullable for a bare `yield`, `delegate` for
  `yield*`) is parsed at the assignment level in `parseAssignment`; `yield*`
  requires an argument. `FunctionDeclaration`/`FunctionExpression` gain an
  `isGenerator()` flag (arrows cannot be generators).
- **Class members** — class methods may be `async`, generator (`*foo(){}`) or async
  generator (`async *foo(){}`); the flags live on the method's `FunctionExpression`
  value, so `MethodDefinition` is unchanged. `async` is treated as a contextual
  modifier (like `static`/`get`/`set`): `async(){}` / `async = 1` are still a member
  named `async`. Getters/setters cannot be async or generators, and an
  async/generator member named `constructor` stays a plain method.
- **Deliberate limitations.** Because the lexer makes `async`, `await` and `yield`
  *keywords* (not contextual identifiers), `async(x)` is never a call and `async`
  cannot be a bare identifier — so `async (…)` is always parsed as arrow params, and
  `await`/`yield` are always parsed as their expressions wherever the keyword
  appears. As in Phase 3 for `super`/`this`, generator/async **context** validity
  (e.g. `yield` only inside a generator) is left to the interpreter.

### Phase 5 — modules, patterns & modern-syntax catch-up 🚧

Delivered in seven independently reviewable sub-phases. The first three complete
the module/pattern grammar; the last four close the remaining ES2020–ES2026
lexer/parser gaps (everything the broad keyword set and modern editions imply but
earlier phases skipped): **5a spread/rest ✅**, **5b destructuring ✅**,
**5c modules ✅**, **5d lexer literals & trivia ✅**, **5e labels & do-while 🚧**,
**5f class enhancements 🚧**, **5g attributes & resource management 🚧**.

#### Phase 5a — spread & rest ✅

- **Spread** — `SpreadElement` (an `Expression`, `argument`) in array literals
  (`[1, ...rest]`), call arguments (`f(...xs)`) and object literals
  (`{...o, a: 1}`). Array/call element lists stay `List<Expression>`;
  `ObjectExpression.properties` is widened to `List<JsNode>` to hold a
  `SpreadElement` alongside `Property` entries, mirroring `ClassBody`.
- **Rest** — `RestElement` (extends `JsNode`, `argument`) as the last function
  parameter (`function f(a, ...rest){}`, `(a, ...rest) => a`); a parameter after a
  rest element, or a `...` with no argument, is a parse error. Function/arrow
  `params` are widened from `List<Identifier>` to `List<JsNode>` (rest elements now,
  full binding patterns in 5b).
- **Array holes** — array-literal elisions (`[a, , b]`, `[,]`) parse to `null`
  elements; a trailing comma (`[a,]`) is not a hole.
- `SpreadElement` and `RestElement` are distinct nodes (`SPREAD_ELEMENT` /
  `REST_ELEMENT`): spread is the expression side, rest the binding side. Both `...`
  productions share the `parseSpreadableExpression` helper on the spread side.

#### Phase 5b — destructuring ✅

- **Destructuring** — array/object binding and assignment patterns
  (`ArrayPattern`, `ObjectPattern`, `AssignmentPattern`, all extending `JsNode`)
  wherever a binding target appears: `var`/`let`/`const` declarations, function/arrow
  parameters (including default params like `f(a = 1)`), assignment LHS, `for-in`/
  `for-of` headers, and `catch` bindings. `RestElement` (from 5a) is reused inside
  patterns (`[a, ...r]`, `{a, ...r}`).
- **Two parsing paths.** *Binding positions* parse patterns directly via
  `parseBindingTarget` (`[` → `ArrayPattern`, `{` → `ObjectPattern`, else
  `Identifier`) and `parseBindingElement` (target + optional `= default` →
  `AssignmentPattern`). *Assignment-LHS positions* use a **cover grammar**: the LHS is
  first parsed as an ordinary `ArrayExpression`/`ObjectExpression` and reinterpreted
  into the matching pattern by `toAssignmentPattern` once a plain `=` proves the intent
  (`[a, b] = arr`, `({a} = o)`); `for-in`/`for-of` expression targets reuse the same
  converter. Only `=` (not compound assignment) reinterprets, and only
  `Identifier`/`MemberExpression` are valid pattern leaves.
- **Widened node fields** (each getter now returns `JsNode`, mirroring the 5a widening
  of `ObjectExpression.properties`): `VariableDeclarator.id`, `CatchClause.param`,
  `AssignmentExpression.target`, and `Property.value` (so an object-pattern property can
  hold a nested pattern or `AssignmentPattern`).
- **CoverInitializedName leniency.** `{a = 1}` is legal only as a destructuring
  pattern, but a one-token lookahead cannot distinguish it from an object literal, so
  `parseProperty` accepts the shorthand-with-initializer and records the value as an
  `AssignmentExpression`; `toAssignmentPattern` converts it to an `AssignmentPattern`. A
  `{a = 1}` not followed by `=` therefore parses as an object expression — validity is
  left to the interpreter, as with `yield`/`super` context in earlier phases.

#### Phase 5c — modules ✅

- **Imports** — `ImportDeclaration` (`specifiers`, `source`) covers bare
  side-effect imports (`import "mod"`), default (`import def from "mod"`),
  namespace (`import * as ns from "mod"`), named (`import { a, b as c } from "mod"`)
  and the combined default-plus-group forms (`import def, { a } from "mod"`,
  `import def, * as ns from "mod"`). The specifier nodes are `ImportSpecifier`
  (`imported`, `local`), `ImportDefaultSpecifier` (`local`) and
  `ImportNamespaceSpecifier` (`local`).
- **Exports** — `ExportNamedDeclaration` (`declaration`, `specifiers`, `source`)
  covers named exports (`export { a, b as c }`), re-exports (`export { a } from
  "mod"`) and declaration exports (`export const/function/async function/class …`,
  where `declaration` is set and `specifiers`/`source` are empty/null);
  `ExportAllDeclaration` (`exported`, `source`) covers `export * from "mod"` and
  `export * as ns from "mod"`; `ExportDefaultDeclaration` (`declaration`) covers
  `export default …`. The named-export specifier is `ExportSpecifier`
  (`local`, `exported`).
- **Contextual `from`/`as`** — both lex as identifiers (not keywords), matched via
  `matchContextualKeyword`/`expectContextualKeyword`, so `let from = 1` and
  `const as = 2` still parse as ordinary declarations. A module name position
  (`parseModuleExportName`) accepts an identifier, a keyword-as-name
  (`{ default as x }`) or a string-literal name (`{ a as "x" }`).
- **Deliberate limitations.** Because the lexer makes `import` a *keyword*, dynamic
  `import(...)` and `import.meta` are not parsed (every `import` begins a
  declaration), mirroring the Phase-4 `async(x)` limitation. `export default
  function f(){}` / `export default class C {}` parse the value through the
  assignment grammar, so a named default export yields a
  `FunctionExpression`/`ClassExpression` rather than a hoisted declaration — the
  distinction is deferred to the interpreter, as with `yield`/`super` context in
  earlier phases. Module *placement* validity (top-level only) and *resolution*
  semantics are likewise interpreter concerns; this phase covers parsing only.

#### Phase 5d — lexer literals & trivia ✅

Pure-lexer additions (plus the two AST leaves they feed), independent of every
other sub-phase.

- **Numeric separators** — a single `_` between digits is accepted in every base
  and position (`1_000`, `0xFF_FF`, `1_000.000_5`, `1_0e1_0`). `lexNumber`
  consumes a `_` only when it sits *between* two valid digits; a leading, trailing,
  doubled, or base-prefix-adjacent `_` simply ends the number (an acceptable parse
  error downstream) rather than being validated in the lexer. The separators are
  stripped (`replace("_", "")`) before `Long.parseLong` / `Double.parseDouble`.
- **BigInt literals** — the `n` suffix on an integer or radix form (`123n`,
  `0xFFn`, `0b1010n`, `1_000n`) produces a new **`JsBigInt`** token
  (`JsType.BIGINT`) carrying a `java.math.BigInteger` — the one place the "all
  numbers are `double`" convention must not truncate, so `JsNumber`/`NumberLiteral`
  are left untouched and a separate **`BigIntLiteral`** node (an `Expression`,
  parsed in `parsePrimary`) holds the exact value. A `.`/exponent form followed by
  `n` is invalid JS and is deliberately *not* recognized (it lexes as a stray
  identifier).
- **Hashbang** — a `#!` at **offset 0** only is skipped to end-of-line as trivia
  (like a comment), emitting no token. Because it is anchored at the start of the
  source it never collides with the private-field `#` of Phase 5f (always inside a
  class body at a non-zero offset).
- **Exhaustive-switch guardrail.** Adding `BIGINT` (and, in 5f, `PRIVATE_IDENTIFIER`)
  to `JsType` forces a new arm in `JsBaseElement.internalGetType` and in the
  parser's exhaustive `describe` switch — the compiler flags any omission, so a new
  token can never be silently undescribed.

#### Phase 5e — labels & do-while 🚧

Pure parser/node additions; no lexer change (`do` is already a keyword).

- **`do…while`** — `DoWhileStatement` (`body`, `test`); a `case "do"` in the
  statement dispatch parses `do <stmt> while ( <expr> )` with an optional trailing
  `;`. This closes a base-grammar (ES1) gap that every earlier phase skipped.
- **Labeled statements** — `LabeledStatement` (`label` `Identifier`, `body`). At
  statement position — after the keyword switch, before the expression-statement
  fallthrough — an `Identifier` immediately followed by a `:` operator is parsed as
  a label wrapping the statement that follows. Ternary `:` is unaffected because it
  is reached only inside expressions.
- **Labeled `break` / `continue`** — `BreakStatement` and `ContinueStatement`
  (previously empty) gain a nullable `label` `Identifier`; `parseBreak`/
  `parseContinue` consume a following identifier as the label. As with every prior
  phase, the newline restriction (`break` and its label must share a line) is
  unenforceable because the lexer discards newlines, so `break\nlabel` is accepted
  — a documented ASI limitation, not a bug.

#### Phase 5f — class enhancements 🚧

- **Private members** — the lexer emits a **`JsPrivateIdentifier`** token
  (`JsType.PRIVATE_IDENTIFIER`, name stored *without* the `#`) for a `#` directly
  followed by an identifier start; a lone `#` stays an
  `UnexpectedCharacterException`. A single **`PrivateIdentifier`** node (an
  `Expression`) then flows through three existing paths with no structural change:
  a member **key** (`parseClassMemberKey` → `#x = 1`, `#m(){}`, `static #s(){}`,
  `get #g(){}`), a member **access** (`parseMemberProperty` → `this.#x`,
  `obj.#m()`, since `MemberExpression.property` is already an `Expression`), and a
  **`#x in obj`** primary (`parsePrimary`, evaluated by the existing `in` binary
  operator). Private-name *validity* (only legal as an `in` LHS or a real member
  reference) is left to the interpreter, mirroring `super`/`this` context handling.
- **Static initialization blocks** — `StaticBlock` (a `List<Statement>` body,
  extending `JsNode` as a class-body member alongside `MethodDefinition`/
  `FieldDefinition`). `matchContextualModifier("static")` already consumes `static`
  when a `{` follows (only `(`/`=`/`;`/`}` exclude it), so `parseClassMember` adds a
  single `static`-then-`{` branch before it tries to read a member key. `static {}`
  (empty) is valid.

#### Phase 5g — attributes & resource management 🚧

The ES2025 import-attribute and ES2026 explicit-resource-management syntax, both
handled through **contextual keywords** (like `from`/`as`/`static`) so no new lexer
keyword is introduced and existing identifier uses keep parsing.

- **Import / export attributes** — a trailing `with { type: "json" }` clause on
  `import def from "mod"`, bare `import "mod"`, `export * from "mod"`, and
  `export { a } from "mod"`. A new **`ImportAttribute`** node (`key`, `value`) holds
  each `IdentifierName`/string **key** paired with a **string-literal** value;
  `ImportDeclaration`, `ExportAllDeclaration`, and `ExportNamedDeclaration` are
  widened with an `attributes` `List` (empty when the `with` clause is absent — the
  only cross-cutting constructor change in the four sub-phases). `with` is matched
  via `matchContextualKeyword`, so `let with = 1` still parses. The legacy `assert`
  spelling is not accepted (`with` is the standardized form).
- **`using` / `await using`** — block-scoped resource declarations
  (`using x = getResource()`, `await using r = f()`) reuse the existing
  **`VariableDeclaration`** node with `kind` = `"using"` / `"await using"` rather
  than introducing a new node — the interpreter branches on `getKind()`. `using` is
  a **contextual identifier**: a statement is a `using` declaration only when
  `using` is directly followed by a binding `Identifier` (so `using;`,
  `using.foo()`, `using = 1`, and `let using = 1` still parse as expressions/
  declarations); `await using` is detected by a three-token lookahead
  (`await` · contextual `using` · identifier), falling back to the ordinary
  `AwaitExpression` path otherwise. Only single-identifier bindings **with** an
  initializer are accepted (destructuring targets and a missing initializer are
  parse errors); the `for (using x of y)` head and full disposal semantics are
  deferred to the interpreter.
- **Deliberate limitations.** As throughout Phase 5, this is parsing only:
  resource-disposal ordering, `Symbol.dispose`/`Symbol.asyncDispose` wiring, the
  legality of `await using` outside an async context, and import-attribute
  *resolution* are all interpreter concerns.

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

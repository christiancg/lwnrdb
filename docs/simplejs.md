# SimpleJS

SimpleJS is a small, dependency-free JavaScript engine embedded in LWNRDB. Its
purpose is to let user-supplied scripts run inside the database — the same
zero-runtime-dependency constraint that governs EJson and the JSON Schema
validator applies here: everything is hand-rolled under
`org.techhouse.simplejs`, with no external parser or JS runtime.

The engine is a classic three-stage pipeline:

```
source String
  → Lexer.lex(source)      → List<JsBaseElement>   (tokens)
  → Parser.parse(tokens)   → Program               (AST)
  → Interpreter.run(ast)   → result                (evaluation)
```

The **lexer** and **parser** are implemented and cover the ES2020–ES2026
syntactic surface. The **interpreter** is being built in sub-phases (see the last
section); **Phases 6a–6d** are done. This document describes the engine as built.
(Sub-phase numbering follows `plans/simplejs-interpreter.md`, which is authoritative.)

> **Status legend:** ✅ implemented · ⬜ not yet built.

## Package layout

| Package | Responsibility |
|---|---|
| `simplejs/elements/` | ✅ Token types produced by the lexer. `JsBaseElement` is the abstract base with a `JsType` enum resolved by a centralized `internalGetType` switch; each concrete token (`JsKeyword`, `JsIdentifier`, `JsPrivateIdentifier`, `JsNumber`, `JsBigInt`, `JsString`, `JsBoolean`, `JsNull`, `JsUndefined`, `JsOperator`, `JsSeparator`, `JsRegex`, `JsTemplateString`, `JsEOF`) is a small immutable class with `getX()` getters. Singletons (`JsNull`/`JsUndefined`/`JsEOF`) use `getInstance()`. `SourcePosition` (offset/length/line/column) is a token-location value held **parallel** to the token stream rather than on the tokens, so the singletons keep their identity. |
| `simplejs/nodes/` | ✅ AST node types produced by the parser. Mirrors the `elements/` convention exactly: an abstract `JsNode` base with a `NodeType` enum resolved by a centralized `internalGetType` switch, plus `Expression`/`Statement` marker abstract subclasses for parser type-safety. |
| `simplejs/internal/` | `Lexer` (✅), `Parser` (✅), and `Interpreter` (✅ phases 6a–6d; later phases ⬜). Each is a `final` class with a public `static` entry point wrapping encapsulated state. `Lexer.lexWithPositions` returns a `LexResult(source, tokens, positions)`; `Lexer.lex` delegates to it and returns just the tokens. `Parser.parse` has a `LexResult` overload (position-aware errors) alongside the token-list overload (index-based errors). `Interpreter.run(Program)` (and the `run(String)` convenience overload that lexes+parses first) tree-walks the AST; it resolves array/string instance methods lazily via `ArrayBuiltins`/`StringBuiltins`, runs a single unified destructuring routine (declarations, params, assignment LHS, `catch`) parameterized by a leaf binder, and (phase 6d) evaluates classes — building a `JsClass`, constructing instances via the field-ordering constructor chain, dispatching methods/getters/setters and `super`, and evaluating private-member access and `instanceof`. The interpreter's runtime helpers `Environment` (scope chain, `this` binding, home-class binding for `super`, function-scope hoisting), `Completion` (control-flow signal), `JsCoercion` (type conversions) and `JsOperators` (operator semantics) live here too. |
| `simplejs/values/` | ✅ (phases 6a–6d) Runtime value model, mirroring the `nodes/` convention: an abstract `JsValue` base with a `JsValueType` enum resolved by a centralized `internalGetType` switch. Concrete types: `JsNumber` (double), `JsString`, `JsBoolean` (`TRUE`/`FALSE` constants), `JsBigInt` (`BigInteger`), `JsUndefined`/`JsNull` (singletons via `getInstance()`), `JsObject` (insertion-ordered property map, with a `freeze` flag for `Object.freeze`, plus a nullable `klass` link + lazy private-field map for class instances), `JsArray`, `JsFunction` (a closure: params, body, captured `Environment`, arrow/expression-body flags), `JsNativeFunction` (a host/built-in function backed by a `BiFunction`, plus an optional static-property map for callable namespaces like `Number.isNaN`), and `JsClass` (phase 6d: a constructable class value holding constructor/instance/static method+accessor tables, static properties, the instance-field list, private-member tables and the shared method scope; `typeof` a class is `"function"`). `EJsonInterop` converts `JsValue ↔ org.techhouse.ejson` elements (used by `JSON.parse`/`stringify`; custom-type mapping is minimal until the DB sub-phase). A dedicated model (not EJson) so JS `undefined`/`null` and coercion rules stay faithful. |
| `simplejs/builtins/` | ✅ (phases 6b–6c) Standard-library values installed into the global scope by `GlobalScope.install`. `ErrorBuiltins` registers the `Error`/`TypeError`/`RangeError`/`SyntaxError` constructors and the `{name, message}` error shape. `ObjectBuiltins` (`keys`/`values`/`entries`/`assign`/`freeze`), `ArrayBuiltins` (callable `Array` + `isArray` and the instance methods `map`/`filter`/`reduce`/`forEach`/`find`/`some`/`every`/`includes`/`indexOf`/`slice`/`splice`/`concat`/`join`/`push`/`pop`/`shift`/`unshift`/`sort`/`flat`), `StringBuiltins` (`slice`/`substring`/`split`/`replace`/`toUpperCase`/`toLowerCase`/`trim`/`includes`/`startsWith`/`endsWith`/`padStart`/`repeat`/`charAt`/`indexOf`), `NumberBuiltins` (callable `Number` + `isNaN`/`isInteger`/`isFinite`/`parseInt`/`parseFloat`), `MathBuiltins`, `JsonBuiltins` (`JSON.parse`/`stringify`, delegating to EJson via `EJsonInterop`), and `ConsoleBuiltins` (`log`/`error`/`warn`/`info`, routed to a redirectable sink — stdout until the host binding lands). Callback-taking array methods call back into user functions through the `Invoker` seam. `String.replace` is literal (no regex). |
| `simplejs/exceptions/` | ✅ Dedicated `RuntimeException` subclasses. Lexer errors: `UnexpectedCharacterException`, `UnterminatedCommentException`, `UnterminatedRegexException`, `UnterminatedStringException`, `UnterminatedTemplateException`. Parser errors: `UnexpectedTokenException`, `UnexpectedEndOfInputException` (each has both a token-index/plain constructor and a line/column constructor). Interpreter errors extend `SimpleJsRuntimeException`: `ReferenceErrorException`, `TypeErrorException`, `RangeErrorException`, `SyntaxErrorException`, `UnsupportedNodeException` (a parsed node outside the current interpreter phase's scope), and `JsThrowException` (carries the `JsValue` thrown by a `throw` statement; unwound by the nearest `try`/`catch`). |

## The lexer

`Lexer.lex(String)` scans source into a `List<JsBaseElement>` terminated by a
`JsEOF` singleton. It handles line/block comments, single/double-quoted strings
with full escape sequences (`\n`, `\xNN`, `\uNNNN`, `\u{...}`), numeric literals
(decimal, `0x`/`0o`/`0b`, exponents, **numeric separators** like `1_000`, and
**BigInt** `n` suffixes → `JsBigInt`), identifiers/keywords, **private
identifiers** (`#x` → `JsPrivateIdentifier`, name stored without the `#`), a
leading **`#!` hashbang** (skipped as trivia at offset 0 only), the multi-character
operator set (longest-match first), separators, template literals (with nested
`${...}` interpolations lexed recursively into sub-token-lists), and the
regex-vs-division ambiguity via the standard "can the previous token end an
expression?" heuristic (`startsRegex`).

The recognized keyword set is `if do while for in of switch case default var let
const break continue return try catch finally throw async await yield function
import export this constructor new class else typeof instanceof void delete
extends super`. Notably **`static`, `get`, `set`, `from`, `as`, `using`, and
`with` are *not* keywords** — they lex as identifiers and the parser treats them
as contextual keywords, so `let from = 1` / `let with = 1` still parse.

> **Lexer constraint that shapes the parser:** the lexer **discards newlines**
> (all whitespace is skipped as token boundaries). This makes newline-sensitive
> Automatic Semicolon Insertion (ASI) impossible from the token stream, so the
> parser uses a pragmatic termination rule instead: a statement ends at `;`, `}`,
> or EOF, and a trailing `;` is optional.

> **Source positions.** The lexer records each token's location — 0-based
> `offset`/`length` plus the 1-based `line`/`column` of its start
> (`elements/SourcePosition`). Because `JsNull`/`JsUndefined`/`JsEOF` are shared
> singletons, positions live in a list **parallel to `tokens`** (one entry per
> token, EOF included) returned by `Lexer.lexWithPositions`, keyed by the parser's
> cursor index. `Parser.parse(LexResult)` reports the offending token's line/column
> in its exceptions; the token-list overload falls back to the token + its index.
> Line/column are derived from `\n` offsets in the source (accurate even though
> newlines are not emitted as tokens); nested template interpolations are lexed
> position-less, so an error inside a `${...}` uses the index-based fallback.

## The AST (`nodes/`)

Every node extends `JsNode`, which mirrors `JsBaseElement`: a `NodeType` enum and
a private `internalGetType` switch with one arm per concrete leaf node. Two empty
abstract subclasses — `Expression` and `Statement` — let parser methods return
`Expression`/`Statement` for type safety. `Program`, `VariableDeclarator`,
`Property`, and the pattern/attribute helper nodes extend `JsNode` directly.
Concrete nodes are small immutable classes with `final` fields and getters,
exactly like the token classes.

## The parser (`Parser.parse`)

The parser is **recursive descent** with a **Pratt / precedence-climbing** core
for expressions. Cursor helpers (`current`, `peek`/`peekAt`, `advance`, `atEnd`),
matchers/expecters (`match*`/`expect*` for separators/operators/keywords, plus
`matchContextualKeyword` for the identifier-keywords above), and an `error()` that
throws the parser exceptions form the substrate. Statement parsing dispatches on
the current token; expression parsing climbs the precedence ladder:

```
assignment  ( = += -= *= /= %= **= <<= >>= >>>= &= |= ^= &&= ||= ??= )  right-assoc
conditional ( ?: )
??  →  ||  →  &&  →  |  →  ^  →  &
==/!=/===/!==   →   < <= > >= instanceof in
<< >> >>>   →   + -   →   * / %   →   ** (right-assoc)
unary       ( ! ~ + - typeof void delete await, prefix ++ -- )
postfix     ( ++ -- )
call/member/new   ( . ?. [] () )
primary     ( literals, identifier, this, super, (), [], {}, function, arrow, template )
```

A `static Map<String,Integer>` holds binary precedences; `**` is the lone
right-associative binary operator. `instanceof` and `in` arrive as `JsKeyword`
tokens (not `JsOperator`), so binary-operator detection checks keyword values too.

### Key design decisions

- **Arrow detection.** An `Identifier` followed by `=>` is a single-param arrow. A
  `(` is treated as arrow params iff a forward scan to the matching `)` is followed
  by `=>` (`matchingParenFollowedByArrow`); otherwise it is a grouping expression.
- **No-in production.** A `for`-header's left-hand side is parsed with a `noIn` flag
  that suppresses `in`-as-operator (cleared inside any bracketed sub-expression via
  `withInAllowed`), so `for (a in b)` disambiguates cleanly.
- **Cover grammar for destructuring.** An assignment-LHS array/object is first parsed
  as an ordinary `ArrayExpression`/`ObjectExpression`, then reinterpreted into the
  matching pattern by `toAssignmentPattern` once a plain `=` proves the intent.
  Binding positions instead parse patterns directly (`parseBindingTarget`/
  `parseBindingElement`).
- **Contextual keywords.** `static`/`get`/`set`/`from`/`as`/`using`/`with` are
  matched only where the grammar expects them, so they remain usable as identifiers.
- **Templates.** `JsTemplateString` carries each `${...}` interpolation as its own
  token list (EOF-terminated), so `parseTemplate` runs a nested parser per
  interpolation.

## Supported grammar

The parser covers the full modern grammar the keyword set implies. By category
(node types in parentheses):

- **Literals** — number, string, boolean, `null`, `undefined`, regex, template,
  and **BigInt** (`123n` → `BigIntLiteral`, holding a `java.math.BigInteger` so the
  "all numbers are `double`" convention never truncates; `NumberLiteral` is
  untouched). Numeric separators (`1_000`, `0xFF_FF`) and a `#!` hashbang are handled
  in the lexer.
- **Expressions** — identifiers, `this` (`ThisExpression`), `super`
  (`SuperExpression`), arrays/objects (shorthand + computed keys + spread +
  elisions), unary/update/binary/logical/assignment/conditional, calls, member
  access (`.`/`[]`/`?.`, including private `this.#x`), `new`, function and arrow
  expressions (incl. `async`), `await` (`AwaitExpression`), `yield`/`yield*`
  (`YieldExpression`), spread (`SpreadElement`), and the `#x in obj` brand check
  (`PrivateIdentifier`).
- **Statements** — `var`/`let`/`const` and **`using`/`await using`** declarations
  (`VariableDeclaration`, the latter two via `kind` `"using"`/`"await using"`),
  blocks, `if`/`else`, `while`, **`do…while`**, C-style `for`, `for-in`/`for-of`,
  `return`/`break`/`continue` (the latter two with an optional label), **labeled
  statements** (`LabeledStatement`), `throw`, `try`/`catch`/`finally`, `switch`,
  function declarations (incl. `async`/generator), class declarations, `import`/
  `export` (all forms), and expression/empty statements.
- **Classes** — declarations and expressions with optional `extends`; a `ClassBody`
  of `MethodDefinition` (plain, `constructor`, `get`/`set`, `static`, `async`,
  generator, async-generator), `FieldDefinition`, **`StaticBlock`** (`static { … }`),
  and **private members** (`#x` fields/methods/accessors).
- **Patterns / destructuring** — `ArrayPattern`/`ObjectPattern`/`AssignmentPattern`
  (+ `RestElement`) wherever a binding target appears: declarations, params (incl.
  defaults), assignment LHS, `for-in`/`for-of` headers, and `catch` bindings.
- **Modules** — `import` (bare/default/namespace/named + combined) and `export`
  (named, re-export, `export *`/`* as ns`, declaration, default), plus ES2025
  **import attributes** (`with { type: "json" }` → `ImportAttribute` on
  `ImportDeclaration`/`ExportAllDeclaration`/`ExportNamedDeclaration`).

## Deliberate limitations (parsing only; deferred to the interpreter)

The parser produces an AST but validates no semantics; the following are intentional
non-goals of the front end:

- **No newline-based ASI** — the lexer discards newlines, so `break\nlabel` and
  similar newline-sensitive rules are not enforced.
- **`import` is a keyword** — dynamic `import(...)` and `import.meta` are not parsed;
  every `import` begins a declaration.
- **No tagged template literals** — a template following an expression (`` tag`x${y}` ``)
  is not parsed as a tag call; `parseCallMemberTail` handles `.`/`?.`/`[]`/`()` but has
  no template arm, so the template is left as a separate primary.
- **No `with` statement** — `with` is only a contextual keyword for import attributes
  (`with { type: "json" }`); the legacy `with (obj) { … }` statement is not parsed.
- **No `debugger` statement** — `debugger` is not a keyword, so `debugger;` parses as an
  ordinary expression statement rather than a dedicated node.
- **`async`/`await`/`yield` are keywords** — `async(x)` is never a call (`async (…)`
  is always arrow params), and `await`/`yield` are always parsed as their
  expressions wherever the keyword appears.
- **Context validity is not checked** — `super`/`this` placement, `yield`/`await`
  only inside the right function kind, private-name references, `await using` outside
  an async context, and `export default function/class` (which yields an expression
  value) are all interpreter concerns.
- **The legacy import-`assert` spelling is not accepted** — only the standardized
  `with` attribute clause is; attribute *resolution* is likewise deferred, as are
  module placement/resolution and explicit-resource-management disposal semantics
  (`Symbol.dispose`/`Symbol.asyncDispose`).

## Phase 6 — the interpreter

A tree-walking `Interpreter` over the AST. It is built in sub-phases so each
increment is small and testable:

- **6a — evaluation core ✅** — the value model (`values/`), lexical
  scopes/environments (`Environment`, with `var` hoisting to the function scope and
  `let`/`const` block scoping + temporal dead zone), control flow via internal
  completion signals (`Completion`: `NORMAL`/`BREAK`/`CONTINUE`/`RETURN`, with
  labeled `break`/`continue`), the full expression grammar (literals incl. BigInt and
  templates, identifiers, member reads incl. `?.`, arrays/objects, unary/update/
  binary/logical/assignment/conditional, `typeof`/`void`/`delete`, `in`) and the
  straight-line & loop statements (`if`/`else`, `while`, `do…while`, C-style `for`,
  blocks, expression/empty statements, `var`/`let`/`const`). Standard operator
  semantics live in `JsOperators`; conversions in `JsCoercion`. `this` is `undefined`
  (no receiver yet) and `instanceof` is deferred. Any parsed node outside 6a's scope
  raises `UnsupportedNodeException`. Not wired into the database yet.
- **6b — functions & control flow ✅** — function declarations/expressions/arrows,
  closures, calls, plain-function `new`, `this`/argument binding, `return`; plus the
  remaining control flow: `throw`, `try`/`catch`/`finally` (a caught runtime error
  arrives as a `{name, message}` object), and `switch` (strict-equality matching,
  fall-through, labeled `break`). A minimal `Error` family (`Error`/`TypeError`/
  `RangeError`/`SyntaxError`) is installed as global constructors. Function parameters
  are identifiers only in 6b — rest/defaults/destructuring params and argument spread
  arrive in 6c; the `arguments` object stays deferred. Method-shorthand object
  properties are a parser gap, so use `key: function () { … }`.
- **6c — objects, arrays, members, destructuring & core built-ins ✅** — spread in
  array/object literals and call arguments; full destructuring (nested, defaults,
  rest, computed keys) in declarations, function params, assignment LHS and `catch`;
  built-in method dispatch on arrays/strings; and the core standard library
  (`Object`/`Array`/`String`/`Number`/`Boolean`/`Math`/`JSON`/`console` + `parseInt`/
  `parseFloat`/`isNaN`/`isFinite`), with `JSON` delegating to EJson via `EJsonInterop`.
  `instanceof` and `for-in`/`for-of` remain deferred. Documented limitations:
  `String.replace` is literal (no regex); `console` writes to stdout until the host
  binding lands; JSON mapping of EJson custom types is minimal. Still not wired into
  the database.
- **6d — classes ✅** — `class` declarations/expressions with `extends` heritage, methods
  (plain/`constructor`/`get`/`set`/`static`), instance and static **fields**, **static blocks**,
  `super` (constructor chaining `super(...)` and member dispatch `super.m()`/`super.prop`),
  **private members** (`#x` fields/methods/accessors + `#x in obj` brand checks), `new` on a class,
  `this` inside methods, and `instanceof`. A dedicated `values/JsClass` value carries the method
  tables, static properties, instance-field list and the shared **method scope** (a class-scope child
  whose home class is bound via `Environment.defineHomeClass`/`resolveHomeClass`, so `super` resolves
  against the lexically-enclosing class rather than the receiver). Instances are plain `JsObject`s
  with a nullable `klass` link and a lazily-created private-field map; instance methods/getters are
  found by `getMember` on an own-property miss (unbound — the member-call path binds `this`).
  Construction follows the standard field-ordering algorithm (base fields before the base
  constructor; derived fields immediately after `super()` returns). Deliberate limitations: async and
  generator class methods are deferred to **6e** (they raise `UnsupportedNodeException`), and
  `instanceof` returns `false` when the right-hand side is a plain-function constructor (only class
  instances carry the `klass` link `instanceof` walks). Still not wired into the database.
- **6e — iteration, generators & async ⬜** — `for-in`/`for-of`, generators/`yield`,
  `async`/`await`, promises, the event loop.
- **6f — modules & host integration ⬜** — `import`/`export`, the `args`/`db` modules,
  enforcement, sandboxing.

The evaluation surface and its integration with the database (how a script is
invoked, what host values and built-ins it sees, and its sandboxing/resource limits)
will be specified in a later sub-phase. That is where SimpleJS becomes useful to the
database rather than a standalone parser.

**Deliberate 6a simplifications** (revisited in later sub-phases): optional chaining
short-circuits per member access rather than across a whole chain (`a?.b.c` still
throws when `a` is nullish); `for (let …)` uses a single loop scope rather than a
fresh per-iteration binding (unobservable until closures arrive in 6b).

## Testing conventions

Tests use **JUnit 5**, live under `src/test/java/org/techhouse/unit/simplejs/`
mirroring the main package structure, and follow the existing `LexerTest` style
(`assertInstanceOf`/`assertEquals`, one-line intent comments):

- **Node tests** (`nodes/JsNodeTest`) — assert `getType()` for every concrete node,
  driving the `internalGetType` switch. Element tokens are covered the same way in
  `elements/JsBaseElementTest`.
- **Lexer tests** (`internal/LexerTest`) — token-level behaviour and lexer errors.
- **Parser unit tests** (`internal/ParserTest`) — AST shape per construct, plus
  negative tests (`assertThrows`) and boundary cases.
- **Program tests** (`internal/LexerProgramTest`, `internal/ParserProgramTest`) —
  full `source → Lexer.lex → Parser.parse` on realistic snippets, the end-to-end
  coverage.

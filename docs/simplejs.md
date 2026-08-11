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
syntactic surface. The **interpreter** is complete: sub-phases **6a–6f** plus every
follow-up (async generators, regex, tagged templates, `using`, the engine-completion
phases, spec-gap Phases A–G, the ES2022–2026 stdlib additions, ASI, always-on strict
mode and the ES2026 conformance phases) are done, so the whole document below
describes the engine **as built**. The engine is reachable only through
`SimpleJs.run(source, HostBindings)`; the `RUN_SCRIPT` wire operation that would
expose it to clients is still a deferred follow-up.

Two lists bound what the engine does **not** do: the
[verified gaps and divergences](#known-gaps-and-divergences-verified-2026-08-11)
(things a conformant engine has that this one is missing or gets wrong — candidates
for closing) and the
[deliberately unimplemented features](#deliberately-unimplemented-es2026-features-out-of-scope-for-a-database-interpreter)
(design decisions, not gaps).

> **Status legend:** ✅ implemented · ⬜ not yet built.

## Package layout

| Package | Responsibility |
|---|---|
| `simplejs/elements/` | ✅ Token types produced by the lexer. `JsBaseElement` is the abstract base with a `JsType` enum resolved by a centralized `internalGetType` switch; each concrete token (`JsKeyword`, `JsIdentifier`, `JsPrivateIdentifier`, `JsNumber`, `JsBigInt`, `JsString`, `JsBoolean`, `JsNull`, `JsUndefined`, `JsOperator`, `JsSeparator`, `JsRegex`, `JsTemplateString`, `JsEOF`) is a small immutable class with `getX()` getters. Singletons (`JsNull`/`JsUndefined`/`JsEOF`) use `getInstance()`. `SourcePosition` (offset/length/line/column) is a token-location value held **parallel** to the token stream rather than on the tokens, so the singletons keep their identity. |
| `simplejs/nodes/` | ✅ AST node types produced by the parser. Mirrors the `elements/` convention exactly: an abstract `JsNode` base with a `NodeType` enum resolved by a centralized `internalGetType` switch, plus `Expression`/`Statement` marker abstract subclasses for parser type-safety. |
| `simplejs/internal/` | `Lexer` (✅), `Parser` (✅), and `Interpreter` (✅ phases 6a–6f). Phase 6f adds the host-aware `Interpreter.run(Program, HostBindings) → ProgramOutcome` (the legacy `run(Program)`/`run(String)` overloads keep returning the last value, now allowing a top-level `return`), `import`/`export` handling (module binding + the return/export result contract), and the sandbox `tick()` checked at loop back-edges and call entries plus a recursion depth cap. Async/generator execution runs on `Coroutine` (a virtual-thread cooperative coroutine) driven by an `EventLoop` microtask queue, both in this package. Each is a `final` class with a public `static` entry point wrapping encapsulated state. `Lexer.lexWithPositions` returns a `LexResult(source, tokens, positions)`; `Lexer.lex` delegates to it and returns just the tokens. `Parser.parse` has a `LexResult` overload (position-aware errors) alongside the token-list overload (index-based errors). `Interpreter.run(Program)` (and the `run(String)` convenience overload that lexes+parses first) tree-walks the AST; it resolves array/string instance methods lazily via `ArrayBuiltins`/`StringBuiltins`, runs a single unified destructuring routine (declarations, params, assignment LHS, `catch`) parameterized by a leaf binder, and (phase 6d) evaluates classes — building a `JsClass`, constructing instances via the field-ordering constructor chain, dispatching methods/getters/setters and `super`, and evaluating private-member access and `instanceof`. The interpreter's runtime helpers `Environment` (scope chain, `this` binding, home-class binding for `super`, function-scope hoisting), `Completion` (control-flow signal), `JsCoercion` (type conversions), `JsOperators` (operator semantics) and `RegexTranslator` (JS regex pattern/flags → `java.util.regex.Pattern`) live here too. Both big classes were later split into sub-packages for maintainability: `internal/parser/` (`ParserTables` precedences, `TokenStream` cursor + `newlineBefore` access, `PatternConverter` cover-grammar conversion) and `internal/interpreter/` (`ExpressionEvaluator`, `StatementEvaluator`, `MemberEvaluator`, `BindingEvaluator`, `ClassEvaluator`, `ModuleEvaluator`, `Iteration`, `ProxyDispatch`, `InterpreterUtils`) — `Parser`/`Interpreter` remain the entry points. |
| `simplejs/values/` | ✅ (phases 6a–6e) Runtime value model, mirroring the `nodes/` convention: an abstract `JsValue` base with a `JsValueType` enum resolved by a centralized `internalGetType` switch. Concrete types: `JsNumber` (double), `JsString`, `JsBoolean` (`TRUE`/`FALSE` constants), `JsBigInt` (`BigInteger`), `JsUndefined`/`JsNull` (singletons via `getInstance()`), `JsObject` (insertion-ordered property map, with a `freeze` flag for `Object.freeze`, plus a nullable `klass` link + lazy private-field map for class instances), `JsArray`, `JsFunction` (a closure: params, body, captured `Environment`, arrow/expression-body flags), `JsNativeFunction` (a host/built-in function backed by a `BiFunction`, plus an optional static-property map for callable namespaces like `Number.isNaN`), and `JsClass` (phase 6d: a constructable class value holding constructor/instance/static method+accessor tables, static properties, the instance-field list, private-member tables and the shared method scope; `typeof` a class is `"function"`). `EJsonInterop` converts `JsValue ↔ org.techhouse.ejson` elements (used by `JSON.parse`/`stringify`; custom-type mapping is minimal until the DB sub-phase). A dedicated model (not EJson) so JS `undefined`/`null` and coercion rules stay faithful. Phase 6e adds `JsPromise` (a pending/fulfilled/rejected promise whose reactions are scheduled on the `EventLoop`) and `JsGenerator` (a generator object wrapping a `Coroutine`); both are `typeof "object"`. `JsAsyncGenerator` (an `async function*` object wrapping a `Coroutine` plus the in-flight next-`JsPromise`; `typeof "object"`) drives the async-iterator protocol. `JsRegExp` (a compiled `java.util.regex.Pattern` plus the JS `source`/`flags` and a mutable `lastIndex` for `g`/`y` matching; `typeof "object"`, `toStr` renders `/source/flags`) backs regex literals and the `RegExp` global. Later phases add `JsSymbol` (+ `SameValueZero`), `JsMap`/`JsSet`/`JsDate`, `JsProxy`, `JsArguments` (mapped/unmapped) and `JsGlobalObject` (the live `globalThis`), and the binary trio `JsArrayBuffer`/`JsTypedArray`/`JsDataView`; `JsObject` grows a prototype link, accessor descriptors, per-key `PropertyFlags` and an `extensible` flag, and `JsFunction` a lazy `prototype`. |
| `simplejs/builtins/` | ✅ (phases 6b–6e) Standard-library values installed into the global scope by `GlobalScope.install`. `ErrorBuiltins` registers the `Error`/`TypeError`/`RangeError`/`SyntaxError` constructors and the `{name, message}` error shape. `ObjectBuiltins` (`keys`/`values`/`entries`/`assign`/`freeze`/`isFrozen`/`seal`/`isSealed`/`preventExtensions`/`isExtensible` — the enumeration methods skip non-enumerable own keys, spec-gap Phase C), `ArrayBuiltins` (callable `Array` + `isArray` and the instance methods `map`/`filter`/`reduce`/`forEach`/`find`/`some`/`every`/`includes`/`indexOf`/`slice`/`splice`/`concat`/`join`/`push`/`pop`/`shift`/`unshift`/`sort`/`flat`), `StringBuiltins` (`slice`/`substring`/`split`/`replace`/`replaceAll`/`match`/`matchAll`/`search`/`toUpperCase`/`toLowerCase`/`trim`/`includes`/`startsWith`/`endsWith`/`padStart`/`repeat`/`charAt`/`indexOf` — the `split`/`replace`/`replaceAll`/`match`/`matchAll`/`search` methods accept a `JsRegExp`; `replace`/`replaceAll` support `$1`/`$<name>`/`$&`/`` $` ``/`$'` tokens and a function replacer), `NumberBuiltins` (callable `Number` + `isNaN`/`isInteger`/`isFinite`/`parseInt`/`parseFloat`), `MathBuiltins`, `JsonBuiltins` (`JSON.parse`/`stringify`, delegating to EJson via `EJsonInterop`), and `ConsoleBuiltins` (`log`/`error`/`warn`/`info`, routed to a per-run sink supplied by `HostBindings.console()`, falling back to a redirectable static sink → stdout). `PromiseBuiltins` (phase 6e) installs `Promise` (`new Promise(executor)`, `resolve`/`reject`/`all`/`race`/`allSettled`/`any`; `any` rejects with an `ErrorBuiltins.makeAggregateError` when all reject). `DbModule` (phase 6f) builds the `db` module object over a `host/DatabaseAccess`. `RegexBuiltins` installs the `RegExp` global (constructable from a string pattern or by cloning a regex) and the `JsRegExp` `test`/`exec` methods + `source`/`flags`/`global`/`ignoreCase`/`multiline`/`dotAll`/`sticky`/`lastIndex` accessors; JS patterns compile to `java.util.regex` via `internal/RegexTranslator` (flags `dgimsuy`; `i`/`m`/`s` map to Java flags, `g`/`y` drive stateful matching, a bad pattern/flag throws a JS `SyntaxError`). `exec`/`match` (non-global)/`matchAll` return a match result object (`[0..n]`, `index`, `input`, named `groups`) rather than a real Array. Callback-taking array methods call back into user functions through the `Invoker` seam. Later phases add `ObjectProtoBuiltins`, `FunctionProtoBuiltins`, `SymbolBuiltins`, `MapBuiltins`/`SetBuiltins`/`DateBuiltins`, `JsIterators`, `TimerBuiltins`, `ReflectBuiltins`/`ProxyBuiltins`, `TypedArrayBuiltins`, `FetchBuiltins`, `IteratorBuiltins`/`AsyncIteratorBuiltins` and `GlobalFunctionsBuiltins` (URI functions, `queueMicrotask`, `structuredClone`, Annex-B `escape`/`unescape`), plus the `InterpreterOps` and `IterableToList` seams back into the interpreter. |
| `simplejs/host/` | ✅ (phase 6f) The DB-integration seam and public entrypoint — the only place the interpreter touches the `ops`/`cache`/`conn`/`ioc` layers. `SimpleJs.run(String, HostBindings) → ScriptResult` is the public API. `HostBindings` carries the `args` payload, a nullable `DatabaseAccess`, a console sink and `ResourceLimits`; `SimpleHostBindings` is a record impl. `DatabaseAccess` is the EJson-typed DB interface (mockable for tests); `EnforcingDatabaseAccess` is the real impl that enforces auth + schema before calling `OperationProcessor`. `ScriptResult` holds the returned/exported EJson value or a thrown error's name+message. |
| `simplejs/exceptions/` | ✅ Dedicated `RuntimeException` subclasses. Lexer errors: `UnexpectedCharacterException`, `UnterminatedCommentException`, `UnterminatedRegexException`, `UnterminatedStringException`, `UnterminatedTemplateException`. Parser errors: `UnexpectedTokenException`, `UnexpectedEndOfInputException` (each has both a token-index/plain constructor and a line/column constructor). Interpreter errors extend `SimpleJsRuntimeException`: `ReferenceErrorException`, `TypeErrorException`, `RangeErrorException`, `SyntaxErrorException`, `UnsupportedNodeException` (a parsed node outside the current interpreter phase's scope), and `JsThrowException` (carries the `JsValue` thrown by a `throw` statement; unwound by the nearest `try`/`catch`). Phase 6f adds `ScriptAbortException` and its subclasses `ScriptTimeoutException`/`ScriptLimitException` — resource-limit aborts that extend `RuntimeException` directly (not `SimpleJsRuntimeException`), so user `try`/`catch` cannot intercept them. |

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

> **Newlines are not tokens, but they are recorded.** The lexer skips all
> whitespace as token boundaries, so a line terminator never reaches the parser as
> a token. Instead the lexer records a **`newlineBefore` flag per token**, parallel
> to the token stream, which is what makes real Automatic Semicolon Insertion
> possible — see the *Automatic Semicolon Insertion* subsection of Phase 6
> below. ASI is active only on the position-aware parse path
> (`Parser.parse(LexResult)`); the test-only token-list overload has no newline
> information and falls back to the permissive rule (a statement ends at `;`, `}`
> or EOF, trailing `;` optional).

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
  interpolation. It also carries the **raw** quasi text (escape sequences left
  verbatim) alongside the cooked text, which tagged templates expose as `strings.raw`.
- **Tagged templates.** A template literal following a call/member expression in
  `parseCallMemberTail` becomes a `TaggedTemplateExpression` (tag + `TemplateLiteral`);
  the interpreter invokes the tag with a frozen strings array (carrying a frozen `raw`
  companion array) followed by the interpolated values. `String.raw` is provided.
  A tagged template in the `new`-callee position (`` new tag`…` ``) is also supported:
  `parseNewCalleeTail` consumes the template into the callee `TaggedTemplateExpression`,
  so `` new tag`x` `` evaluates the tag call and `new`-constructs its result.

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

## Front-end limitations and gaps

The parser produces an AST but validates little semantics. Most of the following are
intentional non-goals of the front end (semantics deferred to the interpreter); the
first two are genuine grammar gaps and are repeated in the gap list below:

- **No sequence (comma) operator** — `(a, b)` as an expression, and therefore the
  common `for (…; …; i++, j++)` update clause, is a `SyntaxError`. Multiple
  declarators (`let i = 0, j = 0`) are unaffected. This is a genuine gap, not a
  design choice (see the gap list below).
- **No `new.target`** — `new.target` is not parsed (`import.meta` is the only
  meta-property recognised).
- **`import` is a keyword** — but dynamic `import(...)` and `import.meta` **are**
  parsed, without un-keywording it: `parseKeywordPrimary` peeks for `(` / `.` and
  emits `ImportExpression`/`MetaProperty` (spec-gap Phase G).
- **No `with` statement** — the contextual `with` of import attributes
  (`with { type: "json" }`) is supported; the legacy `with (obj) { … }` statement is
  rejected as a strict-mode early error.
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
  `with` attribute clause is; attribute *resolution* is likewise deferred, as is
  module placement/resolution. Explicit-resource-management disposal
  (`Symbol.dispose`/`Symbol.asyncDispose`) is implemented by the interpreter (see Phase 6).

## Phase 6 — the interpreter

A tree-walking `Interpreter` over the AST. It was built in sub-phases so each
increment stayed small and testable. **The sub-phase entries below are historical**:
each describes the state at the end of that phase, and a "deferred"/"limitation" note
inside one is superseded whenever a later phase or follow-up section says otherwise
(e.g. 6b's deferred `arguments` object and object method shorthand both landed later).
The authoritative statement of what is *still* missing is
[Known gaps and divergences](#known-gaps-and-divergences-verified-2026-08-11).

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
  `console` writes to stdout until the host binding lands; JSON mapping of EJson custom
  types is minimal. Still not wired into the database.
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
  constructor; derived fields immediately after `super()` returns). Still not wired into the
  database. (Spec-gap Phase B later gave plain functions a `prototype` object, so `instanceof`
  also walks the proto chain for a plain-function RHS — see below.)
- **6e — iteration, generators & async ✅** — `for-in` (own enumerable keys of objects; index
  strings of arrays/strings; nothing for nullish) and `for-of` (arrays, strings, generators), both
  supporting declaration or assignment/destructuring targets, per-iteration `let`/`const` bindings,
  labeled `break`/`continue`, and iterator `close()` on early exit. Generators (`function*`, `yield`,
  `yield*`) and `async`/`await` share **one** mechanism: a `Coroutine` runs the function body on a
  JDK **virtual thread** and hands control back and forth through a single `ReentrantLock` + `Condition`
  so only one thread ever runs at a time (single-threaded JS semantics, no shared-state races). An
  `EventLoop` holds a microtask queue for Promise reactions and coroutine resumes plus a real-time,
  due-time-ordered timer queue (macrotasks) fired after microtasks and bounded by the sandbox
  deadline; `Interpreter.run` drains both to quiescence, then cancels any still-suspended coroutines,
  before returning. A generator
  call returns a `JsGenerator` whose `next`/`return`/`throw` (dispatched lazily via `getMember`, like
  array methods) drive the coroutine and yield `{value, done}`; `return()`/cancellation unwind the
  suspended body through `finally` blocks (`evalTry` runs its finalizer on every exit path). An
  `async` function returns a `JsPromise` and runs its body on a coroutine; `await` coerces its operand
  to a promise, subscribes a microtask to its settlement, and parks until resumed with the value (or
  rethrows the rejection into the body). `builtins/PromiseBuiltins` installs `Promise` (`new
  Promise(executor)`, `resolve`/`reject`/`all`/`race`/`allSettled`/`any`); `allSettled` never rejects
  (each element settles to `{status:"fulfilled",value}` or `{status:"rejected",reason}`), and `any`
  resolves on the first fulfilment or rejects with an `AggregateError` (holding an `errors` array in
  input order) when all reject. The combinators accept any iterable (not just arrays), routed through
  the `IterableToList` seam. `then`/`catch`/`finally` are dispatched on a `JsPromise`. **Async generators** (`async function*`) are supported (follow-up to 6e): they reuse the
  same `Coroutine`, which now reports its pause reason (`YIELD` vs `AWAIT`) and notifies a resume
  observer; a call returns a `JsAsyncGenerator` whose `next`/`return`/`throw` each return a `JsPromise`
  of `{value, done}` (the driver settles it once the body reaches a real `yield`/return, so the body may
  `await` any number of times between yields). `for await (… of …)` (a `ForOfStatement` with an `await`
  flag; `for await` inside a plain — non-async, non-generator — function is a runtime `SyntaxError`) and
  async `yield*` delegation consume async iterables (and sync iterables of promises, awaiting each
  element). Async and generator **class methods** are supported, including async generators. Top-level
  `await`/`for await` **are** supported: the module body runs inside a `Coroutine` driven by the
  `EventLoop`, so `await` at the script root works; top-level `yield` stays a runtime `SyntaxError`
  (`yield` is valid only inside a generator). **Timers** are a real-time macrotask layer on the same
  `EventLoop`: `setTimeout`/`setInterval` enqueue a due-time-ordered timer and `drain(deadlineNanos)`
  becomes a two-tier loop — flush all microtasks, then genuinely wait for and fire the single
  earliest-due timer, repeat — so `Promise.then` runs before `setTimeout(0)` and timers fire in delay
  order; `clearTimeout`/`clearInterval` cancel by id. The wait is **deadline-aware**: a timer due past
  the sandbox wall-clock budget raises `ScriptTimeoutException` rather than over-sleeping, and an
  uncaught throw in a timer callback is swallowed (it does not abort the script). **Unhandled
  rejections** are reported: each `JsPromise` tracks whether a rejection handler was ever attached
  (via `subscribe`), and at end-of-`drain` any still-rejected promise with no handler is reported to
  the host console sink as `UnhandledPromiseRejection: <reason>` (gated by
  `ResourceLimits.reportUnhandledRejections`, default `true`; no-op when the host supplies no console
  sink). Documented limitation: abandoned (never fully consumed) generators are cancelled at
  end-of-run. Because the legacy `run` overloads return the last top-level
  statement value computed **before** the drain, async results observed through those overloads use a
  mutable accumulator (array/object) that the drained reactions mutate; the host `run(…, HostBindings)`
  entrypoint reads the `ProgramOutcome` after the drain, so a top-level `await`ed `return` value is
  returned directly.
  Still not wired into the database.
- **6f — modules & host integration ✅** — the engine is wired to LWNRDB through the
  `simplejs/host/` seam. `SimpleJs.run(String source, HostBindings host)` is the sole public
  entrypoint: it lexes/parses/interprets and returns a `ScriptResult` (an EJson value, or an
  error name+message). **Modules:** a script is one module; `import` resolves only against two
  host-provided built-in modules — `import args from "args"` (the request payload as a map, so
  `args[0]`, `args.name` and `args['name']` all work) and `import db from "db"` (the
  `DatabaseAccess` surfaced as an object of methods `findById`/`aggregate`/`save`/`delete`/
  `listCollections`/`listDatabases`, built by `builtins/DbModule`). All `import` forms
  (default/named/namespace) bind these; any other specifier throws a catchable
  `Cannot find module '…'` error. Import attributes (`with { type: "json" }`) are parsed and
  ignored. **Result contract:** a top-level `return` if the module runs one, else the collected
  `export default`, else an object of the named exports, else `undefined` (→ JSON null).
  **Sandboxing (`ResourceLimits`):** the interpreter checks a per-step instruction budget and a
  wall-clock deadline at loop back-edges and call entries, and a recursion depth cap on each
  call — throwing `ScriptTimeoutException`/`ScriptLimitException` (both extend `ScriptAbortException`,
  which is **not** catchable by user `try/catch` — a `finally` cannot run past the abort either).
  **Enforcement:** `host/EnforcingDatabaseAccess` (the only simplejs class that imports the
  `ops`/`cache`/`conn`/`ioc` layers) resolves the acting `AdminUserEntry`, runs
  `AuthorizationChecker.check` and `SchemaValidationHelper.check`, then calls
  `OperationProcessor.processMessage`; a denial/schema violation throws a JS `Error` into the
  script (catchable). Documented limitations: module resolution is restricted to `args`/`db`
  (no filesystem/network) and import attributes are validated-only. The
  `RUN_SCRIPT` operation that would expose `SimpleJs.run` over the wire is a deferred follow-up
  (not built here).

**Explicit resource management (`using`/`await using`).** A `using`/`await using` declaration
binds a block-scoped const-like resource and runs its disposer when the enclosing scope exits —
on every path (normal, `return`/`break`/`continue`, or a thrown error), in **reverse** declaration
order. `using` calls the resource's `[Symbol.dispose]()`; `await using` `await`s
`[Symbol.asyncDispose]()` (falling back to `[Symbol.dispose]`) and is valid only in an async
context (module top level / async function / async generator — a runtime `SyntaxError` otherwise).
A `null`/`undefined` resource is a no-op; a non-`null` resource without a callable dispose method
throws a `TypeError` at the declaration. When the body throws and a disposer also throws, the errors
aggregate into a `SuppressedError` (`error` = the newest, `suppressed` = the accumulated); a
sandbox abort (`ScriptAbortException`) skips disposal, mirroring `finally`. Disposal is wired into
block, function-body, module-top-level, `for-of`/`for-in` (per iteration), and `switch` scopes.
Symbols exist as a real `JsSymbol` value (`typeof` → `"symbol"`, distinct identity per `Symbol(…)`,
string coercion throws) with the well-known `Symbol.dispose`/`Symbol.asyncDispose` and symbol-keyed
object properties.

**Object model & callable foundations (engine-completion Phase 1).** `JsObject` carries an
optional prototype link (`getProto`/`setProto`); member reads fall back through the `proto`
chain and then a shared `Object.prototype` builtin (`builtins/ObjectProtoBuiltins`:
`hasOwnProperty`, `isPrototypeOf`, `propertyIsEnumerable`, `toString` → `"[object Object]"`,
`valueOf`) on an own-property + class-member miss. `Object` gains `create`, `getPrototypeOf`,
`setPrototypeOf`, `defineProperty`/`defineProperties`, `getOwnPropertyNames`,
`getOwnPropertyDescriptor`, and `fromEntries` (array-of-pairs form). Property descriptors
support a pragmatic subset — `value` and accessor `get`/`set` (stored on the object so member
get/set invoke them); `writable`/`configurable`/`enumerable` are **enforced** as of spec-gap
Phase C (see below). Functions expose
`call`/`apply`/`bind` (`builtins/FunctionProtoBuiltins`); a bound function is a plain native
function tagged with its target + bound args (spec-gap Phase B), so `new` on it constructs the
underlying target with the bound args prepended.
Non-arrow functions receive an `arguments` binding and `globalThis` is installed as a global;
both were plain stand-ins in this phase (a `JsArray` copy of the call arguments and a static
backing object) and were **replaced** by the real exotic objects in spec-gap Phase F — see
*Mapped `arguments` & live `globalThis`* below for the behaviour that holds today.
The `prototype` **objects** of the builtins are not exposed as script-visible properties:
member dispatch is internal, so `Object.prototype`/`Array.prototype`/`String.prototype` read
as `undefined` and builtins cannot be monkey-patched or subclassed (see the gap list).

**ToPrimitive protocol (ES2026 conformance Phase 1).** Object-to-primitive coercion is a real
`OrdinaryToPrimitive`: `JsCoercion.toPrimitive(value, hint, ops)` first consults a callable
`[Symbol.toPrimitive]` (passed the hint), then falls back to `valueOf`/`toString` ordered by the
hint (`"string"` tries `toString` first, `"number"`/`"default"` try `valueOf` first), accepting
the first primitive result and throwing `TypeError` when none is produced. It runs user code, so
it takes an `InterpreterOps ops` seam; the ops-aware `toNumber(value, ops)`/`toStr(value, ops)`
overloads (and `JsOperators.binary`/`unary`/`delta`, whose object operands are coerced with the
right hint — `"number"` for arithmetic/relational/bitwise, `"default"` for `+`/`==`, `"string"`
for template interpolation and `String(x)`) route through it. The legacy no-`ops` overloads
(`ops == null`) keep the old string-only coercion and are used by paths that must **not** call
user code (`EJsonInterop`, `JSON.stringify`, console). Arrays keep their join-based coercion; the
`ops` path intercepts only plain `JsObject`s, so exotic objects (`Date`, `Map`, typed arrays)
retain their dedicated `toStr`/`toNumber` arms.

**Well-known symbol hooks (ES2026 conformance Phase 2).** Four more well-known symbols are real
`JsSymbol` constants (`Symbol.hasInstance`/`toStringTag`/`match`/`replace`/`search`/`split`) and
wired at their choke points: `instanceof` (`ClassEvaluator.evalInstanceof`) consults a callable
`[Symbol.hasInstance]` on the right-hand side before the ordinary heritage/prototype walk (the tested
value is passed as its argument); `Object.prototype.toString` (`ObjectProtoBuiltins`) reads a
string-valued `[Symbol.toStringTag]` and emits `[object <tag>]` (non-string tags are ignored, default
`[object Object]`); and the `String` methods `split`/`replace`/`replaceAll`/`match`/`search` delegate
to a `[Symbol.split]`/`[Symbol.replace]`/`[Symbol.match]`/`[Symbol.search]` method on their argument
when the argument is a plain object exposing one (the `JsRegExp` fast path and plain string/regex
arguments are unchanged). All lookups go through the `InterpreterOps` seam, so absent hooks fall back
to the existing behavior. **`Symbol.species` is intentionally not implemented:** `JsArray`/`JsTypedArray`
carry no constructor/prototype/`klass` linkage and cannot be subclassed (`class X extends Array {}`
requires a `JsClass` superclass), so a user array/typed array can never carry a custom species — the
by-copy methods always allocate the default type. This is a known limitation, not a bug.

**Locale methods & Proxy/Reflect completion (ES2026 conformance Phase 3).** `toLocaleString`
defaults are installed on `Number` (`java.text.NumberFormat` with `Locale.getDefault()`; `NaN`/
`±Infinity` render as `NaN`/`∞`/`-∞`), `Date` (`toLocaleString`/`toLocaleDateString`/
`toLocaleTimeString` via `DateFormat`, UTC zone to match the UTC component model, `Invalid Date`
for a `NaN` time), and `Array` (joins each element's `toLocaleString`, `null`/`undefined` → empty),
and `String.prototype.localeCompare` is now backed by `java.text.Collator` (accent-aware ordering
rather than code-point comparison) — no `Intl` object. The `Proxy` trap set is completed:
`getPrototypeOf`/`setPrototypeOf`/`isExtensible`/`preventExtensions`/`defineProperty`/
`getOwnPropertyDescriptor` are added to `ProxyDispatch` (trap-or-fallback like the existing traps)
and exposed through six new `InterpreterOps` methods so the `Object.*`/`Reflect.*` choke points are
proxy-aware; `Reflect` gains `isExtensible`/`preventExtensions` and routes the prototype/descriptor
statics through the same seam. `values/JsProxy` carries a `revoked` flag and `Proxy.revocable(target,
handler)` returns `{proxy, revoke}` — after `revoke()` every trap (guarded centrally in `trapOf`)
throws `TypeError`. **Not done (deferred):** the `get`/`set` accessor-`receiver` for a trap-less
proxy fallback (the trap receiver itself is already correct) — an invasive member-path change for
negligible observable effect.

**Iterator protocol, symbol keys & object-literal methods (engine-completion Phase 2).**
The well-known `Symbol.iterator`/`Symbol.asyncIterator` are real `JsSymbol` constants, and
`Symbol.for(key)`/`Symbol.keyFor(sym)` provide a process-wide registry of shared symbols.
`for-of`, spread (`[...x]`), and `Object.fromEntries` now consume **any iterable**: a value that
is not an array/string/generator is opened via its `[Symbol.iterator]()` method and driven by the
`next()` → `{value, done}` protocol, calling the iterator's `return()` on an early exit
(`break`/`throw`). `class R { [Symbol.iterator]() {…} }` (and other symbol-keyed methods,
getters/setters, and fields — instance and static) are supported: computed method keys that
evaluate to a symbol route into per-class symbol tables consulted on symbol member reads/writes.
Object literals support **method shorthand** (`{ foo() {} }`, including computed `{ [k]() {} }`
and `async`/generator forms) and **accessors** (`{ get x() {}, set x(v) {} }`, stored as accessor
descriptors so member get/set invoke them); `get`/`set`/`async` stay contextual, so
`{ get: 1 }` remains a plain property and `{ a = 1 }` still parses as a cover-initialized
shorthand.

**Map / Set / WeakMap / WeakSet & Date (engine-completion Phase 3).** Four collection globals and
`Date` are available. `Map`/`Set` are new value types (`values/JsMap`/`values/JsSet`) backed by a
`LinkedHashMap`/`LinkedHashSet` keyed by a **SameValueZero** normalizer (`values/SameValueZero`) so
`+0`/`-0` collapse, `NaN` is a self-equal key, and objects compare by identity; both preserve
insertion order and are iterable via `[Symbol.iterator]` (so `for-of`, spread `[...m]`, and
destructuring work). `Map` exposes `get`/`set`/`has`/`delete`/`clear`/`forEach`/`keys`/`values`/
`entries` + `size`; `Set` exposes `add`/`has`/`delete`/`clear`/`forEach`/`keys`/`values`/`entries`
+ `size` (`keys`/`values`/`entries` return iterator objects). `WeakMap`/`WeakSet` reuse
`JsMap`/`JsSet` and are **strong, not weak** (weakness is unobservable in this sandbox), keeping
the one observable weak constraint: a primitive key/value throws a `TypeError`. `Date`
(`values/JsDate`, an epoch-millis `double`; `NaN` = invalid) supports `new Date()` / `new Date(ms)`
/ `new Date(isoString)` / `new Date(y, m, …)`, the statics `Date.now`/`Date.parse`/`Date.UTC`, and
the usual `getTime`/component getters/setters (UTC and non-UTC variants coincide — the sandbox has
**no local time zone**, everything is UTC), `toISOString`/`toJSON`/`toString`/`valueOf`.
`JSON.stringify(date)` emits the ISO string; a `Map`/`Set` stringifies to `{}` (no own enumerable
properties).

**Standard-library breadth (engine-completion Phase 4).** Widely-used methods/statics/constants fill
out `Number`, `Array` and `String`. `Number` instances resolve `toFixed`/`toPrecision`/
`toExponential`/`toString([radix])`/`valueOf` (via a `JsNumber` arm in `getMember` →
`NumberBuiltins.getMethod`), and the `Number` namespace carries `MAX_SAFE_INTEGER`/
`MIN_SAFE_INTEGER`/`MAX_VALUE`/`MIN_VALUE`/`EPSILON`/`POSITIVE_INFINITY`/`NEGATIVE_INFINITY`/`NaN`.
`Array` adds `findIndex`/`findLast`/`findLastIndex`/`lastIndexOf`/`reduceRight`/`flatMap`/`fill`/
`copyWithin`/`reverse`/`at`/`keys`/`values`/`entries` (the last three return iterator objects) plus
the statics `Array.from` (array-like/iterable + optional map fn) and `Array.of`; arrays stay
iterable through the built-in fast path in `Iteration`. `String` adds `charCodeAt`/`codePointAt`/
`at`/`padEnd`/`trimStart`/`trimEnd`/`normalize`/`localeCompare`/`concat` and the statics
`String.fromCharCode`/`String.fromCodePoint`.

**Optional chaining** short-circuits across a whole chain: once any link with `?.`
observes a nullish base, the rest of the chain is skipped and the expression evaluates
to `undefined` without evaluating later property keys or call arguments (`a?.b.c`,
`a?.b()`, `a?.()` on nullish `a` all yield `undefined`). A non-optional access on a
nullish value still throws (`a.b.c` when `a.b` is nullish). Propagation uses an
internal `SHORT_CIRCUIT` sentinel threaded through the member/call spine and unwrapped
at the top of the chain (`Interpreter.evalMember`/`evalCall`/`evalChainObject`).

**Plain-function `prototype`, `instanceof` & bound-`new` (spec-gap Phase B).** A `JsFunction`
carries a lazily-created `prototype` `JsObject` (with a `constructor` back-reference). `new F()`
links the fresh instance's proto to `F.prototype`, so methods assigned to `F.prototype` resolve
through the instance's proto chain and `x instanceof F` walks that chain (true when any proto link
is `F.prototype`). A bound function (`f.bind(…)`) is tagged with its target + bound args, so `new`
on it constructs the underlying target (bound `this` ignored) and `instanceof` a bound function
delegates to the target. A tagged template in the `new`-callee position (`` new tag`x` ``) is
supported: the tag call is evaluated and its result `new`-constructed.

**Property-descriptor enforcement & true `Object.freeze` (spec-gap Phase C).** `JsObject`
carries a per-key descriptor table (`PropertyFlags(writable, enumerable, configurable)`,
absent ⇒ all-`true`, so normal assignment-created properties stay fully mutable and
enumerable) plus an `extensible` flag. The three attributes are now honoured across the
member-write and enumeration paths: writing a non-writable own data property is a silent
no-op, adding a new key to a non-extensible object is a silent no-op, and non-enumerable
own keys are skipped by `Object.keys`/`values`/`entries`, `for-in`, `Object.assign`, object
spread/rest, `JSON.stringify`, and `propertyIsEnumerable` (while `getOwnPropertyNames` still
lists them). `Object.defineProperty` stores the flags (unspecified attributes default to
`false` for a new property, preserved for a redefinition), throws a `TypeError` when adding a
new key to a non-extensible object, and throws when redefining a non-configurable property in
an incompatible way (making it configurable/enumerable-toggled, or changing the value/writable
of a non-configurable non-writable data property). `getOwnPropertyDescriptor` reports the real
flags. `Object.freeze` marks every own key non-writable + non-configurable and clears
`extensible`; the `seal`/`isSealed`/`preventExtensions`/`isExtensible`/`isFrozen` family
completes the set (an empty non-extensible object is both sealed and frozen). `delete` returns
`false` for a non-configurable property. Redefinition compatibility is a pragmatic subset, not
the full `[[DefineOwnProperty]]` state machine.

**`Reflect` & `Proxy` (spec-gap Phase D).** `builtins/ReflectBuiltins` installs the `Reflect`
namespace — `get`/`set`/`has`/`deleteProperty`/`ownKeys`/`apply`/`construct`/`getPrototypeOf`/
`setPrototypeOf`/`defineProperty`/`getOwnPropertyDescriptor`. Each delegates back into the
interpreter through a single `builtins/InterpreterOps` seam (`getMember`/`setMember`/`has`/
`deleteMember`/`ownKeys`/`call`/`construct`, implemented by the `Interpreter` and threaded via
`GlobalScope.install`); the descriptor/prototype statics reuse `ObjectBuiltins`. `Reflect.set`
returns `true`, `Reflect.defineProperty` returns `false` instead of throwing on an illegal
redefine, and a missing arguments-list argument is treated as empty. `values/JsProxy` is a new
`JsValue` (`JsValueType.PROXY`) holding a `target` + `handler`; its `typeof`/string coercion
mirror the target (`EJsonInterop`/`JsCoercion` delegate through). `new Proxy(target, handler)`
(`builtins/ProxyBuiltins`) requires both to be objects (else `TypeError`). Trap dispatch lives
in the interpreter's member choke points — `getMemberByKey`/`getMember` (`get`),
`setMemberByKey`/`setMember` (`set`), `hasMember`/`in` (`has`), `evalDelete`/`delete`
(`deleteProperty`), `enumerateKeys`/`for-in` + `Object.keys`/`values`/`entries`/
`getOwnPropertyNames` (`ownKeys`), `callValue` (`apply`), and `constructValue`/`new`
(`construct`) — each in a small `proxyGet`/`proxySet`/… helper that falls back to the target
when the trap is absent. A non-function trap throws a `TypeError`. The
`getPrototypeOf`/`setPrototypeOf`/`isExtensible`/`preventExtensions`/`defineProperty`/
`getOwnPropertyDescriptor` traps and `Proxy.revocable` are added in ES2026 conformance Phase 3
(see below). **Remaining limitations**: the `get`/`set` `receiver` argument is passed to the
trap (correct), but when a trap-less proxy falls back to an accessor on the target the accessor's
`this` is the target rather than the proxy; proxy `ownKeys` enumeration does not re-filter through
a `getOwnPropertyDescriptor` trap for enumerability.

**Mapped `arguments` & live `globalThis` (spec-gap Phase F).** A non-arrow function now
receives a purpose-built `values/JsArguments` instead of a plain-array copy. When every
parameter is a plain `Identifier` (no rest/default/destructured param) the object is
**mapped**: numeric-index get/set proxy to the activation `Environment` binding for the
corresponding parameter (built by `Interpreter.makeArguments`), so `arguments[0] = 9` writes
the named parameter and reassigning the parameter is observed through `arguments[0]`. A
rest/default/pattern parameter makes it **unmapped** (a plain backing store, no aliasing).
`length` counts the passed arguments; the object is iterable (`for-of`, spread) via an
`arrayLikeElements` snapshot. Arrows still inherit `arguments` lexically. **Deliberate
limitation**: `arguments.callee` is `undefined` (not a strict-mode throwing accessor).
`globalThis` is a distinguished `values/JsGlobalObject` backed by the global `Environment`:
member reads fall through to the global binding (`Environment.tryGet`), writes assign the
global binding declaring it if absent (`Environment.setGlobal`), and `in` consults it
(`Environment.isDeclared`), so top-level `var`/function declarations and later global
assignments are visible on `globalThis` and `globalThis.x = …` creates a global. **Deliberate
limitations**: `Object.keys(globalThis)`/`for-in` do not enumerate global bindings (builtins
have no enumerability metadata to hide), and symbol-keyed properties on `globalThis` are not
stored.

**Dynamic `import()` / `import.meta` & host-gated `fetch` (spec-gap Phase G).** Dynamic
import and the `import.meta` meta-property are recognised without un-keywording `import`:
the parser emits `nodes/ImportExpression` for `import(specifier[, options])` and
`nodes/MetaProperty` for `import.meta`, both from `parseKeywordPrimary` (and at statement
position via `isDynamicImportOrMeta`, which peeks for `(` or `.`). At runtime
`import(spec)` returns a `JsPromise` resolving to a **module namespace object** — the
resolved host module's own members plus a `default` binding mirroring the default import —
so both `ns.member` and `ns.default` work; an unknown specifier **rejects** with a
catchable `Cannot find module '…'` (parity with static import, still restricted to the
`args`/`db` host built-ins). `import.meta` resolves to `{ url: "simplejs:main" }`.

`fetch` is a host-gated, secure-by-default async global. Network access is a host
capability routed through the `host/` seam exactly like `db`: `host/NetworkAccess`
(`FetchRequest`/`FetchResponse` records) plus `HostBindings.network()` (**default `null`**
→ fetch unavailable). `builtins/FetchBuiltins` installs `fetch(url[, init])` returning a
`JsPromise` of a `Response`-like `JsObject` (`ok`/`status`/`statusText`/`headers` +
async `text()`/`json()`, the latter reusing EJson via `EJsonInterop`). The blocking host
call runs **off the interpreter thread** on a virtual thread and settles the promise via a
new `EventLoop` async-job mechanism (`beginAsyncJob`/`completeAsyncJob`): the drain loop
stays alive while async jobs are outstanding and runs each settlement back on the loop
thread, so single-threaded JS semantics hold and microtasks still order before a fetch
settlement. `host/ResourceLimits` gains `fetchEnabled`, `fetchHostAllowlist`,
`maxResponseBytes`, `fetchTimeoutMillis`; `FetchBuiltins` enforces availability, the host
allowlist (rejected before any call), the response-size cap and a per-call timeout (a
bounded worker join), all producing a catchable `TypeError`. `host/JdkNetworkAccess`
(`java.net.http.HttpClient`) is the only place performing real network I/O and is never
wired by default — a host opts in by supplying it via `network()`. **Deliberate
limitations**: dynamic import resolves only the `args`/`db` built-ins (import options are
ignored), and the `Response` is a plain object (no streaming body, single-value headers).

**Typed arrays (spec-gap Phase E).** Binary data is backed by three new isolated value
types. `values/JsArrayBuffer` wraps a fixed-length shared `byte[]` (`byteLength`, `slice`);
`values/JsTypedArray` is a view over a buffer (buffer ref + `byteOffset` + element `length`
+ a `Kind` enum), reading/writing elements through the buffer's bytes in little-endian order
(the endianness JS exposes for typed arrays) with per-kind coercion — wraparound for the
integer kinds (`Int8`/`Uint8`/`Int16`/`Uint16`/`Int32`/`Uint32`), round-half-to-even
clamping for `Uint8ClampedArray`, IEEE narrowing for `Float32`/`Float64`, and modulo-2⁶⁴
`JsBigInt` elements for `BigInt64`/`BigUint64` (a non-BigInt write throws `TypeError`).
`values/JsDataView` reads/writes numbers and BigInts at an explicit byte offset with an
explicit `littleEndian` flag (big-endian default per spec). `builtins/TypedArrayBuiltins`
supplies the `ArrayBuffer`/`DataView` constructors and the nine number + two BigInt element
constructors (accepting `(length)`, `(array-like/iterable)`, or `(buffer[, offset[,
length]])`), plus the shared `%TypedArray%` prototype methods (`forEach`/`map`/`filter`/
`reduce`/`reduceRight`/`find`/`findIndex`/`some`/`every`/`indexOf`/`lastIndexOf`/`includes`/
`join`/`slice`/`subarray`/`set`/`fill`/`reverse`/`at`/`keys`/`values`/`entries`/`toString`)
and the `from`/`of` statics; `map`/`filter`/`slice` return same-kind copies while `subarray`
returns a new view sharing the buffer. `GlobalScope` installs all constructors. The
interpreter routes `getMember`/`setMember` numeric-index and `length`/`byteLength`/
`byteOffset`/`buffer`/`BYTES_PER_ELEMENT` access to these types, makes them iterable
(`for-of`, spread, `Symbol.iterator`, `Array.from`) via `arrayLikeElements`, and
`JsCoercion`/`EJsonInterop` stringify a typed array as a comma-joined list / JSON array of
its numeric elements (an `ArrayBuffer`/`DataView` → `[object …]` / `{}`). **Deliberate
simplification**: `JSON.stringify` emits a plain JSON array of the elements rather than V8's
index-keyed object form.

**ES2022–2026 standard-library additions (quick wins).** A batch of pure library additions,
no new syntax: `Object.hasOwn(obj, key)` (own-property check over objects and arrays);
`Object.groupBy(items, cb)` and `Map.groupBy(items, cb)` (bucket an iterable by a callback key
into a plain object / a `Map` keyed by SameValueZero — `WeakMap` has no `groupBy`);
`Promise.withResolvers()` (returns `{promise, resolve, reject}`) and `Promise.try(fn, ...args)`
(runs `fn`, adopting a returned promise and turning a synchronous throw into a rejection);
`RegExp.escape(str)` (hex-escapes an alphanumeric first character, backslash-escapes syntax
characters, named-escapes whitespace controls; throws `TypeError` on a non-string);
`Error.isError(x)` (brand check — error objects are tagged internally by `ErrorBuiltins.makeError`,
so a plain `{name, message}` object is **not** an error); the Array by-copy methods
`toReversed`/`toSorted`/`toSpliced`/`with` (return a new array, leaving the receiver intact;
`with` throws `RangeError` out of bounds); `String.prototype.isWellFormed`/`toWellFormed`
(lone-surrogate detection / replacement with U+FFFD); and the seven Set methods
`union`/`intersection`/`difference`/`symmetricDifference`/`isSubsetOf`/`isSupersetOf`/
`isDisjointFrom` (each takes another `Set`; a non-`Set` argument throws `TypeError`).

**Per-iteration loop bindings.** A classic `for (let …; …; …)` creates a fresh lexical
environment for each iteration (spec `CreatePerIterationEnvironment`): after the init runs
in the loop environment, `StatementEvaluator.evalFor` copies the bound `let`/`const` names
forward into a new child environment before each update, so a closure created in the body
captures that iteration's binding (`for (let i = 0; i < 3; i++) fns.push(() => i)` yields
`0, 1, 2`). A `var` (or expression) init keeps the single-scope behaviour. `for-of`/`for-in`
already bound per iteration.

**ES2025 iterator helpers.** The `Iterator` global (`builtins/IteratorBuiltins`) exposes
`Iterator.from` plus a `prototype` carrying `map`/`filter`/`take`/`drop`/`flatMap`/`reduce`/
`toArray`/`forEach`/`some`/`every`/`find`. Calling `Iterator()` directly throws (abstract).
`map`/`filter`/`take`/`drop`/`flatMap` are lazy (return a new iterator whose own result
chains through the same dispatch), the rest consume eagerly. Helpers drive their receiver
directly through the `InterpreterOps` seam (GetIteratorDirect — no `Symbol.iterator`
re-invocation). The interpreter routes helper names on generators (`MemberEvaluator.generatorMethod`)
and on any iterator-like object (own callable `next`) to the helpers, and arrays/strings now
answer `Symbol.iterator` (`getSymbolMember`), so `[1,2,3].values().map(...).toArray()` works.
The async equivalents live in `AsyncIteratorBuiltins` (see the ES2026 conformance closers below).

**Small stdlib globals.** `BigInt(x)` (coerces integer numbers/booleans/integer strings —
`RangeError` on a non-integer number, `SyntaxError` on a bad string, `TypeError` on an
object; `NumberBuiltins.bigIntFunction`); `queueMicrotask(fn)` (enqueues on the existing
`EventLoop` microtask queue); the URI functions `encodeURI`/`decodeURI`/`encodeURIComponent`/
`decodeURIComponent` (RFC-3986 unreserved sets, UTF-8 percent-encoding, `decodeURI` preserves
reserved-character escapes, malformed input throws `URIError`); the Annex-B `escape`/`unescape`
(`%XX`/`%uXXXX`); and `structuredClone(x)` (deep-copy of objects/arrays/Map/Set/Date/typed
arrays/ArrayBuffer with cycle handling; functions/symbols/proxies throw a `DataCloneError`-style
`TypeError`). All live in `builtins/GlobalFunctionsBuiltins` except `BigInt`. `URIError` is
installed by `ErrorBuiltins`.

**Regex `/v`, Float16, resizable buffers.** The regex `v` (unicodeSets) flag is accepted
(`RegexTranslator`, mutually exclusive with `u`) and its set notation is translated (see the
ES2026 conformance closers below); multi-code-point `\q{}` string alternatives remain a documented
limitation. `Float16Array`, `Math.f16round` and `DataView` `getFloat16`/`setFloat16` use the JDK
`Float.float16ToFloat`/`floatToFloat16` half-precision conversions. `ArrayBuffer` supports
resizable/growable buffers: the `{ maxByteLength }` constructor option, `resize`/`transfer`/
`transferToFixedLength` and the `maxByteLength`/`resizable`/`detached` accessors; typed-array
element access is bounds-checked against the buffer's current byte length so a shrunk buffer
reads out-of-range indexes as `undefined`. Length-tracking auto-length views are supported (see
the ES2026 conformance closers below): a view constructed over a resizable buffer with no
explicit length tracks the buffer's current length.

**Unicode property escapes (ES2026 conformance Phase 4).** Under the `u`/`v` flag,
`RegexTranslator` translates `\p{…}`/`\P{…}` property escapes from ECMAScript names to their
`java.util.regex` equivalents: general-category **short codes** (`\p{L}`, `\p{Nd}`) pass through,
**long category names** (`\p{Letter}`, `\p{Decimal_Number}`, and `General_Category=`/`gc=`) map to
the short code, **scripts** (`Script=`/`sc=`/`Script_Extensions=`/`scx=`) map to Java's
`script=` (script-extensions approximated to script), and a supported subset of **binary
properties** (`Alphabetic`, `White_Space`, `Uppercase`, `Lowercase`, `Hex_Digit`, `Ideographic`,
`Assigned`, `Noncharacter_Code_Point`, `Join_Control`) map to the Java `Is…` form. Anything
outside these tables (e.g. `\p{Emoji}`, an unknown key, an invalid script) throws a JS
`SyntaxError`. `Pattern.UNICODE_CHARACTER_CLASS` is **deliberately not** enabled, so `\d`/`\w`/`\s`/
`\b` stay ASCII in `u`-mode exactly as ECMAScript requires (enabling it would make them Unicode-
aware, a conformance regression). `Intl` and `Temporal` remain out of scope.

**Automatic Semicolon Insertion.** The lexer records, parallel to the token stream, a
`newlineBefore` flag per token (`Lexer.LexResult.newlineBefore`) — true when the trivia skipped
immediately before a token contained a line terminator (`\n`/`\r`/U+2028/U+2029), including inside
a multi-line block comment. `TokenStream.newlineBeforeCurrent`/`newlineBeforePeek` expose it, and
`consumeSemicolon` implements the three ASI rules: a terminator is an explicit `;`, or is inserted
before `}`, end-of-input, or a token a line terminator precedes; otherwise a missing terminator on
the same line is a syntax error (so `a = 1 b = 2` is rejected). The **restricted productions** are
enforced: a line terminator makes `return`/`break`/`continue` argument/label-less, makes a postfix
`++`/`--` start a new statement, gives `yield` no argument, is a syntax error after `throw`, and is
a syntax error between arrow parameters and `=>`. ASI runs only on the position-aware parse path
(`Parser.parse(LexResult)`); the token-list overload (`Parser.parse(List)`, test-only) has no
newline information and stays permissive.

**Strict mode (always on).** A script is always a strict module — there is no sloppy mode and no
`"use strict"` directive handling. Two strict runtime behaviors already held before this: assigning
to an undeclared name throws `ReferenceError` (never creates an implicit global,
`Environment.assign`) and `this` in a plain function call is `undefined` (never the global object).
The strict **early errors** are enforced at lex/parse time: legacy-octal and leading-zero
non-octal-decimal integer literals (`0755`, `08`), octal/non-octal string escapes (`\07`, `\1`,
`\8`), duplicate bound parameter names (including inside destructuring patterns), `delete` of an
unqualified identifier or a private reference (`delete x`, `delete this.#p`), the `with`
statement, future-reserved words (`implements`/`interface`/`package`/`private`/`protected`/`public`)
as binding identifiers, and `eval`/`arguments` as binding or assignment/update targets. At runtime
the poisoned `arguments.callee`/`arguments.caller` accessors throw a `TypeError` (see the ES2026
conformance closers below).

## Known gaps and divergences (verified 2026-08-11)

Everything in this section is a **gap**, not a design decision: a conformant engine has it
and SimpleJS either lacks it or gets it wrong. Each row was confirmed by running the snippet
through `SimpleJs.run(source, SimpleHostBindings.empty())` against the built engine — none of
them are inferred from reading the code. The next section lists the features that are missing
*on purpose*.

This section is only as good as its last verification run. The ES2026 conformance closeout
(2026-08-11) closed the gaps it had found — numeric correctness, own-key ordering, strict-mode
write/delete failures, `Object.freeze` on arrays, string iteration by code point, real class
prototypes, `new.target`, object-literal `super`, patchable `Promise`/generator prototypes,
`Symbol.unscopables`, duplicate named capture groups and the top-level-promise contract. A
**follow-up probing pass the same day** found ten more, all now closed (see *ES2026 conformance
follow-up* below): the iteration protocol rejecting a generator-valued `[Symbol.iterator]`,
accessor properties missing from own-key enumeration, `includes` not using SameValueZero,
`Object.prototype` methods rejecting a non-object receiver, the ignored `JSON.parse` reviver,
the missing `Object.getOwnPropertyDescriptors`, `\p{ASCII}`, `Math.sumPrecise`, the two
disposable stacks and the `Uint8Array` base64/hex family. What remains are the bounded
limitations below.

### Remaining limitations

| # | Limitation | Notes |
|---|---|---|
| 1 | **`\k<name>` on a duplicated group name resolves to the first alias** | A duplicated name (`/(?<y>a)\|(?<y>b)/`) compiles by renaming the repeats and resolving `groups.y`/`$<y>` to whichever alias participated, but `java.util.regex` cannot express "whichever alias participated" in a *backreference*, so `\k<y>` always refers to the first one. |
| 2 | **A top-level promise that never settles yields `undefined`** | The result contract awaits a promise returned (or default-exported) at top level, but the event loop has already drained to quiescence, so a promise still pending at that point contributes JSON `null` rather than blocking. |
| 3 | **Class statics stay in tables** | Static methods/getters/setters and static props live on `JsClass` and are resolved by `getStaticMember`; the class object itself is not a real object, so `E.staticMethod = f` works through `setStaticProp` but `Object.keys(E)` does not enumerate statics. Instance members *are* a real `E.prototype` object. |
| 4 | **Generic array-like receivers are snapshotted** | `Array.prototype.push.call(arguments, x)` does not write through — see *Intrinsic prototypes*. |
| 5 | **`super.m()` on a native super is a `TypeError`** | There are no native method tables to chain into. |
| 6 | **`e.stack` is one synthetic frame** and `Function.prototype.toString` retains no source | No interpreter call stack or source text is kept. |
| 7 | **`EJsonInterop` reads data properties only** | The host boundary (the script result and `db` payloads) runs *after* `Interpreter.run` has drained the event loop, so invoking a user getter there would re-enter a finished interpreter. A getter-valued property is therefore absent from the script result, while `JSON.stringify` — the spec-visible path — does invoke it. |

### Host-contract notes

- **A promise returned at top level is awaited.** `return f()` for an `async f` resolves the script
  to the fulfilment value; a rejection becomes the script error (name/message), and a still-pending
  promise resolves to JSON `null`. The same applies to `export default`.
- **`RUN_SCRIPT` is still not wired**, so none of the above is reachable by a client yet.

### Numbers

`ToString(Number)` is spec-exact (ECMA-262 6.1.6.1.20) and **shared with EJson**: one
`ejson/internal/NumberFormatter` backs both `NumberTypeAdapter` (document text and wire responses)
and `JsCoercion` (`String(x)`, templates, `+`). So `String(1e21)` is `"1e+21"`, `String(1e20)` is the
full decimal expansion, and a document field of `1e20` is persisted exactly instead of saturating at
`Long.MAX_VALUE`. Integer conversions (`|`, `>>>`, `Math.imul`/`clz32`, typed-array and `DataView`
integer writes) use the spec's modulo-2³² `ToInt32`/`ToUint32` rather than a saturating `(long)` cast.
On the read side the EJson lexer accepts exponent notation and `JsonNumber` keeps an out-of-`int`-range
integral value as a `double`.

### Intrinsic prototypes

Builtin methods live on **realm-scoped prototype objects** built by `builtins/Intrinsics`, one
`Intrinsics` instance per `Interpreter` (i.e. per `SimpleJs.run`). They are deliberately **not**
static: a shared `Array.prototype` would let one tenant's monkey-patch poison every later script in
the JVM. `PrototypeProgramTest.test_realm_isolation` asserts the isolation.

Each prototype's own properties are **delegating wrappers** — one `JsNativeFunction` per method name
that coerces the receiver at call time and re-dispatches into the untouched family class
(`ArrayBuiltins`, `StringBuiltins`, …). Entries are installed non-enumerable but writable and
configurable, so `Object.keys([])`, `for-in` and `JSON.stringify` are unaffected while
`Array.prototype.join = f` and `delete Array.prototype.push` both work.

`MemberEvaluator` keeps each value type's own state handling first (array/string/typed-array index,
`length`, `Map`/`Set` `size`, the regex flag accessors, …) and then walks
`Intrinsics.protoFor(target)` and its `getProto()` chain, which roots at `Object.prototype`. A user
object's own `proto` chain is still walked before the intrinsic chain.

`Promise.prototype` and the generator/async-generator prototypes are real prototypes on the same
model: `PromiseBuiltins.PROTO_NAMES` (`then`/`catch`/`finally`) and `GeneratorBuiltins.PROTO_NAMES`
(`next`/`return`/`throw`) are installed as delegating wrappers, so patching them takes effect. They
are reachable from script through `Promise.prototype` and, for generators, through
`Object.getPrototypeOf(gen)` — `Object.getPrototypeOf` on a value that is not a `JsObject` returns
its intrinsic prototype, as the spec requires.

**User classes have real prototypes too.** `JsClass` owns a `prototype` `JsObject` whose entries are
non-enumerable (so instances still serialize as their own state), carrying a `constructor`
back-reference and `proto`-linked to the superclass's prototype (or, for builtin heritage, the native
prototype). Instances are linked to it at construction, keeping the `klass` link for private members,
brand checks and field initialisation. Only *static* members remain in `JsClass` tables.

What this reaches and what it does not:

- `Array.prototype.slice.call(arguments)` works. An **array-like receiver is snapshotted** into a
  fresh `JsArray`, so a *mutating* generic call (`Array.prototype.push.call(arguments, x)`) does not
  write through — accepted limitation, and the reason the wrapper name appears in the `TypeError`.
- A wrong-type receiver throws a `TypeError` naming the method
  (`Array.prototype.push called on an incompatible receiver 1`).
- **Builtin subclassing** works via `JsClass.nativeSuperClass`: heritage that resolves to a
  `JsNativeFunction` carrying a prototype is accepted, `super(...)` runs the native constructor, and
  the instance is linked to the native prototype so both `instanceof E` and `instanceof Error` hold.
  A builtin with internal state (`Map`/`Set`/`Date`/`Array`/typed arrays) cannot be copied onto a
  plain instance, so the produced value is kept as the instance's wrapped primitive and the intrinsic
  wrappers unwrap it from their receiver — `class M extends Map {}` gets working `set`/`get`/`size`.
- `super.m()` against a native super is an explicit `TypeError` (there are no native method tables to
  chain into), not a silent wrong answer.
- `Function` is installed so `Function.prototype` resolves and `f instanceof Function` holds, but
  calling or `new`-ing it throws `TypeError("Function constructor is disabled")` — runtime code
  generation stays outside the sandbox.
- A **caught runtime error is a real error object** proto-linked to the matching intrinsic prototype,
  so `try { null.x } catch (e) { e instanceof TypeError }` is `true` and `e.toString()` is
  `"TypeError: …"`.
- `e.stack` is a **single synthetic frame** (`"<name>: <message>\n    at <script>"`); no interpreter
  call stack is retained. `Function.prototype.toString` likewise returns
  `"function <name>() { [native code] }"` for closures too — no source text is kept.

## Deliberately unimplemented ES2026 features (out of scope for a database interpreter)

The engine targets ES2026 semantics for code that runs inside the database. The following
standard features are intentionally **not** implemented — each is either a sandbox/security
boundary or unobservable in a single-threaded, per-request interpreter, so omitting them is a
design decision, not a bug:

- **`eval` / the `Function` constructor** — no runtime code generation from strings. Allowing it
  would defeat the instruction-budget/deadline sandbox and open an injection surface.
- **`Intl`** — the internationalization API is enormous; only ad-hoc `toLocaleString`/
  `localeCompare` defaults (backed by `java.text`) are provided.
- **`Temporal`** — the date/time proposal is out of scope; `Date` is the only temporal type.
- **`SharedArrayBuffer` + `Atomics`** (including `Atomics.pause`) — shared-memory multithreading is
  meaningless in a single-threaded per-connection VM.
- **Immutable `ArrayBuffer`** (`transferToImmutable`/`sliceToImmutable`) — the proposal is not in the
  ES2026 snapshot; buffers are mutable or detached, with no third state.
- **`WeakRef` / `FinalizationRegistry`** — GC-observable behavior cannot be exposed safely or
  deterministically; `WeakMap`/`WeakSet` exist but are strong (weakness is unobservable in-sandbox).
- **Arbitrary module resolution** — `import` resolves only the host `args`/`db` built-ins;
  filesystem/URL module loading would be a sandbox escape.
- **`Symbol.species`** — `JsArray`/`JsTypedArray` are not subclassable, so species is unobservable;
  by-copy methods always allocate the default type.
- **The `with` statement** — forbidden in strict mode, so it is a `SyntaxError` here.
- **Proper tail calls** — no TCO (observable only via deep-recursion stack behavior).

### ES2026 conformance follow-up (2026-08-11)

A probing pass run *after* the closeout below found ten further defects, all closed in four phases:

- **The iteration protocol accepts any object-like iterator.** `Iteration.openIterator` demanded a
  `JsObject`, so an object whose `[Symbol.iterator]` is a generator method
  (`class C { *[Symbol.iterator]() {…} }`) was rejected — a generator is a sibling `JsValue`, not a
  `JsObject`. The iterator is now typed `JsValue` and checked with a deny-list `isObjectLike` (every
  non-primitive is an object to the spec), which restores `for-of`, spread, `Array.from`, `yield*`
  and `new Set(obj)` over such an iterable, and also accepts a returned `Map`/`Set`/proxy iterator.
  Array destructuring was moved onto the same `Iteration` choke point — it drove `iterableElements`
  directly, so it never honoured the iterator protocol at all — and now pulls lazily and `close()`s
  the iterator when the pattern ends early. The returned generator is driven through the member path
  (not the coroutine fast path), so a patched `Generator.prototype.next` is honoured.
- **Accessor properties participate in own-key enumeration.** Data properties and accessors live in
  separate maps, so their relative insertion order was unrecoverable; `JsObject` now keeps one
  insertion-ordered `keyOrder` set as the single source of truth for own string keys, maintained by
  `set`/`defineValue`/`defineAccessor`/`delete`, and `keys()` orders that (the private `ownKeys()`
  collapsed into it). `delete o.x` now also drops the accessor entries, which it previously left
  live. Because `JsObject` cannot call a getter by design, the value-reading consumers route through
  a shared `InterpreterUtils.ownValue(object, key, ops)` — `Object.values`/`entries`/`assign`,
  object spread, rest destructuring, `JSON.stringify`, `structuredClone`, `Object.create`/
  `defineProperties` and `Array.prototype.concat` on a spreadable object. `EJsonInterop` deliberately
  does **not** (see *Remaining limitations* #7).
- **Small conformance fixes.** `Array.prototype.includes` and the typed-array `includes` use
  SameValueZero (`SameValueZero.equal`) instead of delegating to `indexOf`, so `[NaN].includes(NaN)`
  is `true`, `[-0].includes(0)` is `true` and a hole reads as `undefined`; `indexOf` keeps strict
  equality. Every `Object.prototype` method accepts any receiver: `toString` uses the spec brand
  table (`[object Array]`/`Number`/`Date`/`Null`/`Arguments`/…, with a string `Symbol.toStringTag`
  still winning), `hasOwnProperty`/`propertyIsEnumerable` share `ObjectBuiltins.hasOwnKey` (extended
  to string and typed-array indices) and throw a `TypeError` on `null`/`undefined`, and
  `isPrototypeOf` walks `Intrinsics.protoFor` and the implicit terminal `Object.prototype` link, so
  `Array.prototype.isPrototypeOf([])` is `true`. `JSON.parse` implements the `InternalizeJSONProperty`
  reviver walk (bottom-up, mutating in place, an `undefined` result deleting the key).
  `Object.getOwnPropertyDescriptors` was added, and `getOwnPropertyDescriptor` learned symbol keys.
  `\p{ASCII}` and `\p{Any}` are translated (`ASCII`/`all`); every other name that `java.util.regex`
  cannot express still throws an honest `SyntaxError`.
- **Missing ES2026 library surface.** `Math.sumPrecise` (exact `BigDecimal` accumulation rounded
  once; empty → `-0`, mixed infinities → `NaN`, a non-Number element → `TypeError`);
  `DisposableStack`/`AsyncDisposableStack` (`use`/`defer`/`adopt`/`dispose`/`disposeAsync`/`move`,
  the `disposed` getter and `[Symbol.dispose]`/`[Symbol.asyncDispose]`, reverse-order disposal with
  `SuppressedError` aggregation, entries held under a module-private symbol key so the backing array
  never leaks onto the stack); and the `Uint8Array` base64/hex family (`toBase64`/`toHex`/
  `setFromBase64`/`setFromHex` on the `Uint8Array` prototype only, plus `Uint8Array.fromBase64`/
  `fromHex`). Symbol lookup now walks the prototype chain, so a prototype-installed well-known symbol
  such as `[Symbol.dispose]` resolves on an instance and answers `in`.

### ES2026 conformance closeout (2026-08-11)

The final conformance pass, in five phases:

- **Numeric correctness** — one `ejson/internal/NumberFormatter` implements the spec
  `Number::toString` and `ToInt32`/`ToUint32` and is shared by EJson and SimpleJS (see *Numbers*
  above). It also fixes a database-wide defect: an integral double past `Long.MAX_VALUE` used to be
  persisted as `9223372036854775807`. On the read side the EJson lexer learned exponent notation and
  `JsonNumber` keeps an out-of-`int`-range integral value as a `double` instead of clamping it.
  `Math.imul` was added and `Math.trunc`/`clz32`, `toFixed` above 1e21, a large radix conversion and
  the integer typed-array/`DataView` writes stopped saturating.
- **Object-model conformance** — `JsObject.keys()` reports `OrdinaryOwnPropertyKeys` order (canonical
  array-index keys ascending, then insertion order), and every enumeration site routes through it, so
  `Object.keys`/`values`/`entries`, `for-in`, `Reflect.ownKeys`, `Object.assign`, object spread/rest,
  `structuredClone` and `JSON.stringify` all agree. A rejected write or `delete` now throws a
  `TypeError` (the engine is always strict) while `Reflect.set`/`Reflect.deleteProperty` keep
  returning `false`; `Object.freeze`/`seal`/`preventExtensions` and their predicates reach `JsArray`.
- **String iteration by code point** — the iterator-protocol paths (`for-of`, spread, array
  destructuring, `String`'s `Symbol.iterator`, `Array.from`) walk a string by code point, so
  `[..."ab😀"].length` is 3. Indexed access, `length`, `split("")`, object spread of a string and the
  generic array-like receiver snapshot stay code-unit based, as the spec's string exotic object requires.
- **Class prototypes, `new.target`, object-literal `super`, patchable `Promise`/generator prototypes** —
  `JsClass` now owns a real `prototype` `JsObject` (non-enumerable entries, `constructor`
  back-reference, `proto`-linked to the superclass prototype), instances are linked to it, and member
  resolution/`super` go through the proto chain, so `E.prototype.m = f`, `delete E.prototype.m` and
  `Object.getPrototypeOf(new E())` all behave. `new.target` is parsed as a `MetaProperty` and bound at
  construction (arrows inherit it lexically). Object-literal shorthand methods and accessors get a home
  object, so they may call `super`. `Promise.prototype` and the generator/async-generator prototypes
  carry real delegating wrappers (`PromiseBuiltins.PROTO_NAMES`, `GeneratorBuiltins.PROTO_NAMES`), and
  `Object.getPrototypeOf` on a non-object value returns its intrinsic prototype, so those are reachable
  and patchable. `Symbol.unscopables` exists as a stable well-known symbol.
- **Regex duplicate named groups + host contract** — `RegexTranslator` renames a repeated `(?<name>)`
  and keeps an alias table on `JsRegExp`, so ES2025 duplicate names in different alternatives compile
  and `groups.name`/`indices.groups.name`/`$<name>` resolve to whichever alias participated. A promise
  returned (or default-exported) at top level is awaited by `SimpleJs.contractResult`.

### ES2026 conformance closers (previously completed)

The following were previously listed as gaps and are now implemented:

- **Strict early errors** — future-reserved words (`implements`/`interface`/`package`/`private`/
  `protected`/`public`) as binding identifiers, `eval`/`arguments` as binding or assignment/update
  targets, and the poisoned `arguments.callee`/`caller` accessors are all rejected (parse-time for
  the binding/assignment errors, a `TypeError` at runtime for `callee`/`caller`). Reserved words
  remain valid as property keys.
- **Regex `v` (unicodeSets) set notation** — `RegexTranslator` now parses `v`-mode character classes
  and rewrites the set notation to `java.util.regex` form: subtraction `A--B` → `[A&&[^B]]`,
  intersection `A&&B`, nested-class union, and single-code-point `\q{…}` string alternatives (a
  multi-code-point `\q{}` member throws a `SyntaxError` — documented limitation). Property escapes
  are still translated inside `v`-mode classes.
- **Length-tracking typed-array views** — a typed array (or `DataView`) constructed over a resizable
  `ArrayBuffer` with **no** explicit length now recomputes its element `length`/`byteLength` from the
  buffer's current byte length on every access, so it grows and shrinks with `resize`. An
  explicit-length view or a view over a non-resizable buffer keeps its construction-time length. A
  `DataView` read past the current length throws a `RangeError`.
- **`globalThis` enumeration** — `Object.keys`/`values`/`entries`/`getOwnPropertyNames` and `for-in`
  enumerate user-declared global bindings (`var`/function declarations and `globalThis.x = …`
  assignments). Host builtins are installed as non-enumerable, so they are not reported. Lexical
  top-level `let`/`const` are not properties of the global object.
- **Async iterator helpers** — `AsyncIterator.prototype` supplies `map`/`filter`/`take`/`drop`/
  `flatMap`/`reduce`/`toArray`/`forEach`/`some`/`every`/`find` plus `AsyncIterator.from`; the first
  five are lazy. They are routed onto async generators and async-iterator-like objects and drive the
  receiver through promises on the event loop (awaiting both a promise-returning `next()` and a
  promise-returning callback result). `for await` also consumes a plain async iterator via
  `Symbol.asyncIterator`.
- **`[[DefineOwnProperty]]` completeness** — `Object.defineProperty` now rejects a non-configurable
  property's data↔accessor kind change, an accessor `get`/`set` identity change, and a non-writable
  data-property value change under **SameValue** (so `+0`→`-0` throws while `NaN`→`NaN` is allowed).
- **`Proxy` `get`/`set` receiver + `ownKeys` enumerability** — a trap-less proxy's accessor fallback
  now threads the proxy as the `this`/receiver (and `Reflect.get`/`Reflect.set` honour an explicit
  receiver), and `Object.keys`/`values`/`entries` + `for-in` re-filter an `ownKeys` trap's result
  down to enumerable string keys.
- **Intrinsic prototypes and builtin subclassing** — `Object.prototype`/`Array.prototype`/
  `String.prototype`/`Number.prototype`/… and a `Function` global are real objects, prototype
  monkey-patching works, and `class E extends Error {}` (or `Map`/`Set`/`Array`/…) produces a usable
  instance. See *Intrinsic prototypes* above for the model and its limits.
- **Static private class members** — `static #x = 1`, `static #m()` and static private accessors are
  declared into their own tables, so `A.#x`, `A.#m()`, `this.#m()` inside a `static` method and the
  `#x in A` brand check all work; another class's static private name is still a `TypeError`.
- **`UnsupportedNodeException` no longer escapes `SimpleJs.run`** — the entrypoint ends in a terminal
  `catch (SimpleJsRuntimeException)`, mapping an unsupported node to a `SyntaxError` result and any
  other sibling to `InternalError`.
- **Sequence (comma) operator** — `nodes/SequenceExpression`; `parseExpression` collects a comma list
  (a single operand is returned unwrapped), so `(a, b)`, `return (1, 2)` and `for (…; …; i++, j++)`
  all work. Call arguments and array/object literals still parse with `parseAssignment`.
- **`array.length` assignment** — truncates or pads with holes, and rejects a negative, fractional or
  `NaN` length with a `RangeError`.
- **`JSON.stringify` `replacer` / `space`** — a function replacer (called with `(key, value)` and the
  holder as `this`), an array key allowlist, `toJSON`, cycle detection (`TypeError`) and
  non-enumerable-key skipping happen while building the EJson tree; rendering delegates to the new
  `EJson.toJson(element, indent)` so escaping and number formatting stay in one place. `space` is a
  number (capped at 10) or a string (first 10 chars).
- **Array holes** — `JsArray` marks an elision with a distinct `JsUndefined` instance, so every reader
  that does not opt in still sees `undefined`. `forEach`/`map`/`filter`/`some`/`every`/`find*`/
  `reduce*`/`indexOf`/`lastIndexOf` skip holes (`map` preserves them), `join`/`toString` render them
  empty, `sort` moves them last, `in` reports them absent, `JSON.stringify` emits `null`, and spread
  materialises them as real `undefined`.
- **Object-literal `__proto__`** — a non-computed `__proto__` key sets the prototype; a computed
  `['__proto__']` key still creates an own property and a non-object, non-null value is ignored.
- **Symbol keys in copy operations** — `Object.assign`, object spread and object rest-destructuring
  copy symbol-keyed properties after the string keys.
- **`matchAll` without `g`**, **`$$` in a replacement**, **`split` `limit`**, **`toFixed` rounding**
  (exact-binary `BigDecimal`, so `(1.005).toFixed(2)` is `"1.00"`) and the **Annex-B string methods**
  (`substr`, `toLocale{Upper,Lower}Case`, `trimLeft`/`trimRight`).
- **Library surface** — global `NaN`/`Infinity`/`undefined`; the full `Math` namespace bar `imul`;
  `Object.is`/`getOwnPropertySymbols`, `Number.isSafeInteger`; `Function` `name`/`length`/`toString`;
  `Error` `cause`/`stack`/`toString`; BigInt `toString([radix])`/`valueOf`/`toLocaleString` +
  `BigInt.asIntN`/`asUintN`; typed-array `sort`/`toSorted`/`toReversed`/`with`/`findLast`/
  `findLastIndex`/`copyWithin`; `Array.prototype.toString` and `Array.fromAsync`; `Symbol`
  `description`/`toString` and the `Symbol.matchAll`/`isConcatSpreadable` hooks; regex `d`-flag
  `indices` (numbered and named groups, `undefined` for a non-participating group); and primitive
  wrapper objects (`new String/Number/Boolean`) plus `new Object()`.

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
- **Interpreter tests** (`internal/Interpreter*Test` — expressions/statements,
  `Object`, `Class`, `Generator`, `Async`, `AsyncGenerator`, `Iteration`, `Using`,
  `Module`, `Timer`, `Sandbox` — plus `EnvironmentTest`, `CoroutineTest`,
  `EventLoopTest`, `JsCoercionTest`, `JsOperatorsTest`) and the feature program tests
  (`AsiProgramTest`, `StrictModeProgramTest`, `ProxyProgramTest`,
  `TypedArrayProgramTest`, `DynamicImportProgramTest`, `GlobalProgramTest`,
  `FunctionProgramTest`, `AsyncIteratorHelperTest`, `RegexTranslatorVFlagTest`) —
  behaviour asserted through `Interpreter.run`/`SimpleJs.run`.
- **Value / builtin / host tests** (`values/`, `builtins/`, `host/`) — per-type and
  per-library-family units, `EJsonInterop` both directions, and the host seam
  (`EnforcingDatabaseAccess` against a real `Cache`/`OperationProcessor`: an allowed
  save, a denied save, a schema-violating save).

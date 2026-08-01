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
| `simplejs/internal/` | `Lexer` (✅), `Parser` (✅), and `Interpreter` (✅ phases 6a–6f). Phase 6f adds the host-aware `Interpreter.run(Program, HostBindings) → ProgramOutcome` (the legacy `run(Program)`/`run(String)` overloads keep returning the last value, now allowing a top-level `return`), `import`/`export` handling (module binding + the return/export result contract), and the sandbox `tick()` checked at loop back-edges and call entries plus a recursion depth cap. Async/generator execution runs on `Coroutine` (a virtual-thread cooperative coroutine) driven by an `EventLoop` microtask queue, both in this package. Each is a `final` class with a public `static` entry point wrapping encapsulated state. `Lexer.lexWithPositions` returns a `LexResult(source, tokens, positions)`; `Lexer.lex` delegates to it and returns just the tokens. `Parser.parse` has a `LexResult` overload (position-aware errors) alongside the token-list overload (index-based errors). `Interpreter.run(Program)` (and the `run(String)` convenience overload that lexes+parses first) tree-walks the AST; it resolves array/string instance methods lazily via `ArrayBuiltins`/`StringBuiltins`, runs a single unified destructuring routine (declarations, params, assignment LHS, `catch`) parameterized by a leaf binder, and (phase 6d) evaluates classes — building a `JsClass`, constructing instances via the field-ordering constructor chain, dispatching methods/getters/setters and `super`, and evaluating private-member access and `instanceof`. The interpreter's runtime helpers `Environment` (scope chain, `this` binding, home-class binding for `super`, function-scope hoisting), `Completion` (control-flow signal), `JsCoercion` (type conversions), `JsOperators` (operator semantics) and `RegexTranslator` (JS regex pattern/flags → `java.util.regex.Pattern`) live here too. |
| `simplejs/values/` | ✅ (phases 6a–6e) Runtime value model, mirroring the `nodes/` convention: an abstract `JsValue` base with a `JsValueType` enum resolved by a centralized `internalGetType` switch. Concrete types: `JsNumber` (double), `JsString`, `JsBoolean` (`TRUE`/`FALSE` constants), `JsBigInt` (`BigInteger`), `JsUndefined`/`JsNull` (singletons via `getInstance()`), `JsObject` (insertion-ordered property map, with a `freeze` flag for `Object.freeze`, plus a nullable `klass` link + lazy private-field map for class instances), `JsArray`, `JsFunction` (a closure: params, body, captured `Environment`, arrow/expression-body flags), `JsNativeFunction` (a host/built-in function backed by a `BiFunction`, plus an optional static-property map for callable namespaces like `Number.isNaN`), and `JsClass` (phase 6d: a constructable class value holding constructor/instance/static method+accessor tables, static properties, the instance-field list, private-member tables and the shared method scope; `typeof` a class is `"function"`). `EJsonInterop` converts `JsValue ↔ org.techhouse.ejson` elements (used by `JSON.parse`/`stringify`; custom-type mapping is minimal until the DB sub-phase). A dedicated model (not EJson) so JS `undefined`/`null` and coercion rules stay faithful. Phase 6e adds `JsPromise` (a pending/fulfilled/rejected promise whose reactions are scheduled on the `EventLoop`) and `JsGenerator` (a generator object wrapping a `Coroutine`); both are `typeof "object"`. `JsAsyncGenerator` (an `async function*` object wrapping a `Coroutine` plus the in-flight next-`JsPromise`; `typeof "object"`) drives the async-iterator protocol. `JsRegExp` (a compiled `java.util.regex.Pattern` plus the JS `source`/`flags` and a mutable `lastIndex` for `g`/`y` matching; `typeof "object"`, `toStr` renders `/source/flags`) backs regex literals and the `RegExp` global. |
| `simplejs/builtins/` | ✅ (phases 6b–6e) Standard-library values installed into the global scope by `GlobalScope.install`. `ErrorBuiltins` registers the `Error`/`TypeError`/`RangeError`/`SyntaxError` constructors and the `{name, message}` error shape. `ObjectBuiltins` (`keys`/`values`/`entries`/`assign`/`freeze`/`isFrozen`/`seal`/`isSealed`/`preventExtensions`/`isExtensible` — the enumeration methods skip non-enumerable own keys, spec-gap Phase C), `ArrayBuiltins` (callable `Array` + `isArray` and the instance methods `map`/`filter`/`reduce`/`forEach`/`find`/`some`/`every`/`includes`/`indexOf`/`slice`/`splice`/`concat`/`join`/`push`/`pop`/`shift`/`unshift`/`sort`/`flat`), `StringBuiltins` (`slice`/`substring`/`split`/`replace`/`replaceAll`/`match`/`matchAll`/`search`/`toUpperCase`/`toLowerCase`/`trim`/`includes`/`startsWith`/`endsWith`/`padStart`/`repeat`/`charAt`/`indexOf` — the `split`/`replace`/`replaceAll`/`match`/`matchAll`/`search` methods accept a `JsRegExp`; `replace`/`replaceAll` support `$1`/`$<name>`/`$&`/`` $` ``/`$'` tokens and a function replacer), `NumberBuiltins` (callable `Number` + `isNaN`/`isInteger`/`isFinite`/`parseInt`/`parseFloat`), `MathBuiltins`, `JsonBuiltins` (`JSON.parse`/`stringify`, delegating to EJson via `EJsonInterop`), and `ConsoleBuiltins` (`log`/`error`/`warn`/`info`, routed to a per-run sink supplied by `HostBindings.console()`, falling back to a redirectable static sink → stdout). `PromiseBuiltins` (phase 6e) installs `Promise` (`new Promise(executor)`, `resolve`/`reject`/`all`/`race`/`allSettled`/`any`; `any` rejects with an `ErrorBuiltins.makeAggregateError` when all reject). `DbModule` (phase 6f) builds the `db` module object over a `host/DatabaseAccess`. `RegexBuiltins` installs the `RegExp` global (constructable from a string pattern or by cloning a regex) and the `JsRegExp` `test`/`exec` methods + `source`/`flags`/`global`/`ignoreCase`/`multiline`/`dotAll`/`sticky`/`lastIndex` accessors; JS patterns compile to `java.util.regex` via `internal/RegexTranslator` (flags `dgimsuy`; `i`/`m`/`s` map to Java flags, `g`/`y` drive stateful matching, a bad pattern/flag throws a JS `SyntaxError`). `exec`/`match` (non-global)/`matchAll` return a match result object (`[0..n]`, `index`, `input`, named `groups`) rather than a real Array. Callback-taking array methods call back into user functions through the `Invoker` seam. |
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

## Deliberate limitations (parsing only; deferred to the interpreter)

The parser produces an AST but validates no semantics; the following are intentional
non-goals of the front end:

- **No newline-based ASI** — the lexer discards newlines, so `break\nlabel` and
  similar newline-sensitive rules are not enforced.
- **`import` is a keyword** — dynamic `import(...)` and `import.meta` are not parsed;
  every `import` begins a declaration.
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
  `with` attribute clause is; attribute *resolution* is likewise deferred, as is
  module placement/resolution. Explicit-resource-management disposal
  (`Symbol.dispose`/`Symbol.asyncDispose`) is implemented by the interpreter (see Phase 6).

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
Non-arrow functions receive an `arguments` binding that is a **real** `JsArray` copy of the
call arguments (not the exotic, argument-aliasing `arguments` object); arrows inherit the
enclosing `arguments` lexically. `globalThis` is a backing object that reflects the installed
builtins and top-level values written through `GlobalScope.define` — it is **not** a fully live
mirror of every lexical binding.

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
Async iterator helpers are deferred.

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
(`RegexTranslator`, mutually exclusive with `u`) and behaves as Unicode mode — `/v` set
notation and string-property escapes are not translated. `Float16Array`, `Math.f16round` and
`DataView` `getFloat16`/`setFloat16` use the JDK `Float.float16ToFloat`/`floatToFloat16`
half-precision conversions. `ArrayBuffer` supports resizable/growable buffers: the
`{ maxByteLength }` constructor option, `resize`/`transfer`/`transferToFixedLength` and the
`maxByteLength`/`resizable`/`detached` accessors; typed-array element access is bounds-checked
against the buffer's current byte length so a shrunk buffer reads out-of-range indexes as
`undefined`. Length-tracking auto-length views are not supported (a view keeps its
construction-time element length).

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

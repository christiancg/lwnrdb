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
| `simplejs/values/` | ✅ (phases 6a–6e) Runtime value model, mirroring the `nodes/` convention: an abstract `JsValue` base with a `JsValueType` enum resolved by a centralized `internalGetType` switch. Concrete types: `JsNumber` (double), `JsString`, `JsBoolean` (`TRUE`/`FALSE` constants), `JsBigInt` (`BigInteger`), `JsUndefined`/`JsNull` (singletons via `getInstance()`), `JsObject` (insertion-ordered property map, with a `freeze` flag for `Object.freeze`, plus a nullable `klass` link + lazy private-field map for class instances), `JsArray`, `JsFunction` (a closure: params, body, captured `Environment`, arrow/expression-body flags), `JsNativeFunction` (a host/built-in function backed by a `BiFunction`, plus an optional static-property map for callable namespaces like `Number.isNaN`), and `JsClass` (phase 6d: a constructable class value holding constructor/instance/static method+accessor tables, static properties, the instance-field list, private-member tables and the shared method scope; `typeof` a class is `"function"`). `EJsonInterop` converts `JsValue ↔ org.techhouse.ejson` elements (used by `JSON.parse`/`stringify`; custom-type mapping is minimal until the DB sub-phase). A dedicated model (not EJson) so JS `undefined`/`null` and coercion rules stay faithful. Phase 6e adds `JsPromise` (a pending/fulfilled/rejected promise whose reactions are scheduled on the `EventLoop`) and `JsGenerator` (a generator object wrapping a `Coroutine`); both are `typeof "object"`. `JsRegExp` (a compiled `java.util.regex.Pattern` plus the JS `source`/`flags` and a mutable `lastIndex` for `g`/`y` matching; `typeof "object"`, `toStr` renders `/source/flags`) backs regex literals and the `RegExp` global. |
| `simplejs/builtins/` | ✅ (phases 6b–6e) Standard-library values installed into the global scope by `GlobalScope.install`. `ErrorBuiltins` registers the `Error`/`TypeError`/`RangeError`/`SyntaxError` constructors and the `{name, message}` error shape. `ObjectBuiltins` (`keys`/`values`/`entries`/`assign`/`freeze`), `ArrayBuiltins` (callable `Array` + `isArray` and the instance methods `map`/`filter`/`reduce`/`forEach`/`find`/`some`/`every`/`includes`/`indexOf`/`slice`/`splice`/`concat`/`join`/`push`/`pop`/`shift`/`unshift`/`sort`/`flat`), `StringBuiltins` (`slice`/`substring`/`split`/`replace`/`replaceAll`/`match`/`matchAll`/`search`/`toUpperCase`/`toLowerCase`/`trim`/`includes`/`startsWith`/`endsWith`/`padStart`/`repeat`/`charAt`/`indexOf` — the `split`/`replace`/`replaceAll`/`match`/`matchAll`/`search` methods accept a `JsRegExp`; `replace`/`replaceAll` support `$1`/`$<name>`/`$&`/`` $` ``/`$'` tokens and a function replacer), `NumberBuiltins` (callable `Number` + `isNaN`/`isInteger`/`isFinite`/`parseInt`/`parseFloat`), `MathBuiltins`, `JsonBuiltins` (`JSON.parse`/`stringify`, delegating to EJson via `EJsonInterop`), and `ConsoleBuiltins` (`log`/`error`/`warn`/`info`, routed to a per-run sink supplied by `HostBindings.console()`, falling back to a redirectable static sink → stdout). `PromiseBuiltins` (phase 6e) installs `Promise` (`new Promise(executor)`, `resolve`/`reject`/`all`/`race`). `DbModule` (phase 6f) builds the `db` module object over a `host/DatabaseAccess`. `RegexBuiltins` installs the `RegExp` global (constructable from a string pattern or by cloning a regex) and the `JsRegExp` `test`/`exec` methods + `source`/`flags`/`global`/`ignoreCase`/`multiline`/`dotAll`/`sticky`/`lastIndex` accessors; JS patterns compile to `java.util.regex` via `internal/RegexTranslator` (flags `dgimsuy`; `i`/`m`/`s` map to Java flags, `g`/`y` drive stateful matching, a bad pattern/flag throws a JS `SyntaxError`). `exec`/`match` (non-global)/`matchAll` return a match result object (`[0..n]`, `index`, `input`, named `groups`) rather than a real Array. Callback-taking array methods call back into user functions through the `Invoker` seam. |
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
  constructor; derived fields immediately after `super()` returns). Deliberate limitations:
  `instanceof` returns `false` when the right-hand side is a plain-function constructor (only class
  instances carry the `klass` link `instanceof` walks). Still not wired into the database.
- **6e — iteration, generators & async ✅** — `for-in` (own enumerable keys of objects; index
  strings of arrays/strings; nothing for nullish) and `for-of` (arrays, strings, generators), both
  supporting declaration or assignment/destructuring targets, per-iteration `let`/`const` bindings,
  labeled `break`/`continue`, and iterator `close()` on early exit. Generators (`function*`, `yield`,
  `yield*`) and `async`/`await` share **one** mechanism: a `Coroutine` runs the function body on a
  JDK **virtual thread** and hands control back and forth through a single `ReentrantLock` + `Condition`
  so only one thread ever runs at a time (single-threaded JS semantics, no shared-state races). An
  `EventLoop` holds a microtask queue for Promise reactions and coroutine resumes; `Interpreter.run`
  drains it to quiescence, then cancels any still-suspended coroutines, before returning. A generator
  call returns a `JsGenerator` whose `next`/`return`/`throw` (dispatched lazily via `getMember`, like
  array methods) drive the coroutine and yield `{value, done}`; `return()`/cancellation unwind the
  suspended body through `finally` blocks (`evalTry` runs its finalizer on every exit path). An
  `async` function returns a `JsPromise` and runs its body on a coroutine; `await` coerces its operand
  to a promise, subscribes a microtask to its settlement, and parks until resumed with the value (or
  rethrows the rejection into the body). `builtins/PromiseBuiltins` installs `Promise` (`new
  Promise(executor)`, `resolve`/`reject`/`all`/`race`); `then`/`catch`/`finally` are dispatched on a
  `JsPromise`. Async and generator **class methods** are supported (async-generator methods remain
  deferred → `UnsupportedNodeException`). Documented limitations: no timers/macrotasks (no
  `setTimeout`), no top-level `await`/`yield` (a runtime `SyntaxError`), unhandled promise rejections
  are silently ignored, and abandoned (never fully consumed) generators are cancelled at end-of-run.
  Because `run` returns the last top-level statement value computed **before** the drain, async
  results are observed through a mutable accumulator (array/object) that the drained reactions mutate.
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
  (no filesystem/network), import attributes are validated-only, `using`/`await using` disposal
  and async generators remain deferred, and no `setTimeout`/timers. The `RUN_SCRIPT` operation
  that would expose `SimpleJs.run` over the wire is a deferred follow-up (not built here).

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

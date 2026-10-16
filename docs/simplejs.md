# SimpleJS

SimpleJS is a small, dependency-free JavaScript engine embedded in LWNRDB, so user-supplied
code can run *inside* the database. The same zero-runtime-dependency constraint that governs
EJson and the JSON Schema validator applies here: everything lives under
`org.techhouse.simplejs`, with no external parser, regex library or JS runtime.

```
source String
  → Lexer.lex(source)         → List<JsBaseElement>   (tokens)
  → Parser.parse(tokens)      → Program               (AST)
  → Interpreter.run(program)  → result                (evaluation)
```

Everything outside `simplejs/host/` is a self-contained engine; `host/` is the only package
that touches the `ops`/`cache`/`conn`/`ioc` layers, and it is where the database's own
surfaces (`RUN_SCRIPT`, stored procedures, triggers, schedules, pipeline scripts) attach.

| Entry point | Use |
|---|---|
| `SimpleJs.run(source \| CompiledScript, HostBindings) → ScriptResult` | run a program to completion (drains the event loop, applies the result contract) |
| `SimpleJs.compile(source, strictScriptGoal) → CompiledScript` | parse once, run many times (stored procedures, cached ad-hoc scripts) |
| `SimpleJs.openCallable(compiled, host) → ScriptCallable` | keep one interpreter alive and call its exported function repeatedly, on the *caller's* thread (pipeline scripts, before-write hooks) |
| `SimpleJs.moduleSpecifiers(compiled)` | the program's static `import` specifiers, so a save can be validated without exposing the AST |

## Engine status — closed

The language surface and the host contract are complete, and no further *engine* work is
planned — what comes next are database features that use SimpleJS. Three things make that
checkable rather than declarative:

- **Conformance is measured, not asserted**: 100.00% (41,078/41,078) of the filtered
  tc39/test262 corpus, with an empty baseline. See *Measuring conformance*.
- **The gaps list is residue, not a roadmap.** What remains is unreachable from ordinary
  procedure/trigger/schedule code — see *Known gaps and divergences*.
- **Every host surface a database script needs is wired**: ad-hoc scripts, stored procedures,
  before-write hooks and after triggers (exactly-once, retry, dead letters), scheduled
  procedures, pipeline scripts, cursors, single- and cross-owner transactions, captured
  console output, run history, per-run metrics, real call stacks, cancellation, per-tenant
  admission and cluster placement by script load.

Three things are deliberately left open, none of them engine work:

1. **`scriptMaxMemoryBytes` is a bulk-allocation budget, not a live-heap cap.** A script that
   allocates a small object per instruction is bounded only by `scriptInstructionBudget`, so the
   two must be sized together (see *Sandbox and resource limits*). The process-wide backstop for
   what the budget cannot see is `scriptHeapCeilingPercent`.
2. **Outbound `fetch` ships on, with its allowlist at `*`** — a capability behind a default-off
   flag is one nobody discovers. `*` means any **public** host: loopback, private, link-local
   (the 169.254.169.254 metadata endpoint), CGNAT, unique-local and the other non-public ranges
   are refused unless the exact host is listed, so the shipped posture no longer hands a script
   the server's position *inside* the network — but it still lets any script make the server
   call any public host. `StartupWarnings.warnIfScriptFetchEnabled` says so at every startup,
   and raises a SECURITY WARNING when an exact entry re-opens `localhost` or a literal private
   address; operators are expected to narrow it.
3. **npm packages are a non-goal**, not a pending phase. Resolution is the smaller half: a real
   package (`mysql2` is the worked example) needs raw TCP sockets, `Buffer`, `stream`,
   `EventEmitter` and asymmetric crypto — i.e. a Node runtime, which is the boundary this
   document draws everywhere else. A long-lived bidirectional socket also has no natural size
   or time bound and cannot be policed by a host allowlist the way `fetch` is. The two supported
   ways to reach an external database are a host-side connector (named, credentialed
   datasources, not built) or HTTP in front of it (possible today with `fetch`).

## The language surface

The engine targets **ES2026 semantics** and is **always strict** — there is no sloppy mode and
no `"use strict"` directive handling. Assigning to an undeclared name is a `ReferenceError`,
`this` in a plain call is `undefined`, and the strict early errors are enforced at lex/parse
time (legacy-octal literals and octal string escapes, duplicate parameter names, `delete` of an
unqualified or private reference, `with`, future-reserved words as bindings, `eval`/`arguments`
as assignment targets, `const` without an initializer, lexical redeclaration).

What is implemented is, in short, the modern language: the full expression grammar and all
control flow, functions/arrows/closures, classes (heritage, accessors, static and instance
fields, static blocks, private members, `new.target`), destructuring and spread/rest, template
and tagged-template literals, generators, `async`/`await`, async generators, `for await`,
iterators and the iterator protocol (including the iterator helpers), symbols and the
well-known symbol hooks, proxies and `Reflect`, property descriptors and `Object.freeze`,
modules (static and dynamic `import`, every `export` form, import attributes), explicit
resource management (`using`/`await using`), BigInt, typed arrays/`ArrayBuffer`/`DataView`
(resizable and length-tracking included), regular expressions with all of `dgimsuvy`, ASI, and
the standard library: `Object`/`Array`/`String`/`Number`/`Boolean`/`Math`/`JSON`/`Map`/`Set`/
`WeakMap`/`WeakSet`/`Date`/`Promise`/`RegExp`/`Symbol`/`Reflect`/`Proxy`/`Error` (+
`AggregateError`/`SuppressedError`), the URI functions, `structuredClone`, `queueMicrotask`,
timers, `console`, `crypto`, and the whole ISO-8601 `Temporal` API.

Two things bound what it does *not* do: [Known gaps and divergences](#known-gaps-and-divergences)
(a conformant engine has it, this one is missing it or gets it wrong) and
[Deliberately unimplemented ES2026 features](#deliberately-unimplemented-es2026-features)
(design decisions, not gaps).

## How it is built

### Package layout

| Package | Responsibility |
|---|---|
| `elements/` | Token types. `JsBaseElement` is an abstract base with a `JsType` enum resolved by one `internalGetType` switch; concrete tokens are small immutable classes, with `JsNull`/`JsUndefined`/`JsEOF` as singletons. `SourcePosition` holds a token's offset/length/line/column **parallel** to the token stream, so the singletons keep their identity. |
| `nodes/` | AST nodes, mirroring the `elements/` convention (a `NodeType` enum + `Expression`/`Statement` marker subclasses). Nodes carry their source position and, for function-like productions, their verbatim source text. |
| `internal/` | `Lexer`, `Parser`, `Interpreter` — each a `final` class with a public static entry point over encapsulated state — plus the runtime helpers `Environment`, `Completion`, `JsCoercion`, `JsOperators`, `Coroutine`, `EventLoop` and `RegexTranslator`. Each of the three delegates to its own sub-package. `internal/lexer/` holds the scanners (`LexerTables`, `CharClasses`, `StringLexer`, `NumberLexer`, `IdentifierLexer`, `TemplateLexer`, `RegexLiteralLexer` plus the `Lexed`/`EscapeResult`/`BraceContext` carriers). `internal/parser/` is an abstract chain over `TokenStream` — `GrammarPredicates` -> `ParserContext` (the cursor plus the 14 grammar-context flags, and the abstract seams the layers call upward through) -> `LoopProductions` -> `StatementProductions` -> `ModuleProductions` -> `BindingProductions` -> `ExpressionProductions` -> `ClassProductions` -> `LiteralProductions` -> `ParserProductions` — because the productions are mutually recursive over one moving cursor, which no split into free functions survives. `Interpreter` itself is a facade: its own dispatch, member I/O, function construction and run accounting live beside it in `EvalDispatch`, `MemberIo`, `FunctionFactory` and `RunLifecycle`, with `Session`/`ModuleResult` as the run's result types, and the facade keeps a delegating seam for each so callers still go through `Interpreter`. `internal/interpreter/` holds the evaluator collaborators: `ExpressionEvaluator` (+ `ObjectLiteralEvaluator`, `AssignmentEvaluator`, `DeleteEvaluator`), `StatementEvaluator` (+ `LoopEvaluator`, `ForInEnumeration`, `LoopAction`), `ClassEvaluator` (+ `ClassMemberInstaller`, `ClassConstruction`, `SuperAccess`, `StaticEntry`), `MemberEvaluator` + `MemberWriter`/`BuiltinMemberLookup`/`AsyncGeneratorDriver`, `BindingEvaluator`, `ModuleEvaluator`/`ModuleLifecycle`, `FunctionInvoker`, `ConstructEvaluator`, `HasMemberEvaluator`, `PrivateMemberEvaluator`, `MemberAccessEvaluator`, `Iteration`, `ProxyDispatch`, `ModuleRegistry`, `CallStack`, `InterpreterUtils`. A collaborator is reached through the class that owns the state it mutates, never constructed by a caller. `internal/regex/` is the regex engine, whose parser is the same kind of chain (`RegexCursor` -> `EscapeReader` -> `CharacterClassParser` -> `SetClassParser` -> `RegexProductions`), and `internal/temporal/` the calendar/duration infrastructure plus the shared `TimeUnits`/`TemporalLimits` tables. |
| `values/` | The runtime value model: `JsValue` + `JsNumber`/`JsString`/`JsBoolean`/`JsBigInt`/`JsUndefined`/`JsNull`/`JsSymbol` (primitives) and `JsObject`/`JsArray`/`JsFunction`/`JsNativeFunction`/`JsClass`/`JsPromise`/`JsGenerator`/`JsAsyncGenerator`/`JsMap`/`JsSet`/`JsDate`/`JsRegExp`/`JsProxy`/`JsArguments`/`JsGlobalObject`, the binary trio `JsArrayBuffer`/`JsTypedArray`/`JsDataView`, the eight `JsTemporal*` types and the four EJson bridges `JsGeo`/`JsVector`/`JsDbDateTime`/`JsDbTime`. A dedicated model rather than EJson, so `undefined`/`null` and JS coercion stay faithful. `EJsonInterop` converts in both directions; the object keys it hands to EJson come from the shared `ejson/internal/KeyTable`, so a script that saves many documents with the same field names stores one string per name, not one per document. |
| `builtins/` | The standard library, installed by `GlobalScope.install`, one class per family (`ObjectBuiltins`, `ArrayBuiltins`, `StringBuiltins`, …). A family that outgrew one file keeps its entry point here and moves its internals into a sub-package: `builtins/array/`, `builtins/typedarray/`, `builtins/iterator/`, `builtins/temporal/`, `builtins/string/`, `builtins/object/`, `builtins/regex/`, `builtins/json/`, `builtins/promise/`, `builtins/date/`, `builtins/intrinsics/`. `Intrinsics` builds the realm's prototype objects, with `IntrinsicsBrands` (brand checks), `IntrinsicsInstallers`, `IntrinsicsPrototypes` (prototype wiring) and `IntrinsicsCoercion` (`toObject`/`wrapPrimitive` and the prototype wrappers) under `builtins/intrinsics/`. `BuiltinArgs`, `NewTargetSupport` and the `builtins/temporal/` option/field readers (`TemporalOptions`, `TemporalFields`, `TemporalFieldReader`, `MonthCode`) are the shared helpers those families call instead of re-implementing. The `InterpreterOps`, `Invoker`, `IterableToList` and `TextImporter` seams are how a builtin calls back into the interpreter without depending on `internal/`. |
| `host/` | The DB-integration seam and public entrypoint: `SimpleJs`, `HostBindings` (+ `SimpleHostBindings`, `DatabaseHostBindings`, `PipelineHostBindings`, `HookHostBindings`), `ResourceLimits`, `DatabaseAccess`/`EnforcingDatabaseAccess`, `NetworkAccess`/`JdkNetworkAccess`, `ModuleResolver`/`ProcedureModuleResolver`, `CancellationToken`, `ConsoleCapture`, `ScriptResult`/`ScriptRunMetrics`. |
| `exceptions/` | Lexer/parser errors; interpreter errors extending `SimpleJsRuntimeException` (`ReferenceErrorException`, `TypeErrorException`, …) plus `JsThrowException`; and `ScriptAbortException` with its `ScriptTimeoutException`/`ScriptLimitException`/`ScriptCancelledException` subclasses, which extend `RuntimeException` directly so user `try`/`catch` cannot intercept them. |

### The front end

The lexer produces a token list terminated by `JsEOF`, plus two parallel arrays: each token's
`SourcePosition` and a `newlineBefore` flag. Newlines are never tokens, but recording them is
what makes real **ASI** possible: `consumeSemicolon` accepts an explicit `;`, a `}`, EOF or a
preceding line terminator, and the restricted productions (`return`/`break`/`continue`/`throw`,
postfix `++`/`--`, `yield`, arrow `=>`) are enforced. `constructor`, `static`, `get`, `set`,
`from`, `as`, `using` and `with` are **not** keywords — they lex as identifiers and the parser
treats them contextually, so `let from = 1` still parses. Identifiers accept the full
`ID_Start`/`ID_Continue` surface plus `\u` escapes (an escaped reserved word is a `SyntaxError`,
per spec).

The parser is recursive descent with a Pratt core for expressions. Three decisions shape it:
a `(` is arrow parameters iff a forward scan to the matching `)` is followed by `=>`; a `for`
header's left-hand side is parsed under a `noIn` flag (the spec's no-in production); and an
assignment-LHS array/object is parsed as an ordinary literal and reinterpreted into a pattern
once a plain `=` proves the intent (the cover grammar). Binding positions parse patterns
directly. The parser also owns the declaration early errors, keeping a stack of
`DeclarationScope`s in which a lexical name must be unique and must not collide with a `var`,
parameter or function name reaching the same scope — they live here because the negative tests
assert that no statement executes.

Function-like productions record a **source-text span** (`JsNode.sourceText`) that
`Function.prototype.toString` hands back verbatim, comments and all. Spans follow the spec's
productions rather than the enclosing statement: a method's span opens at its first modifier or
its key (`static` belongs to the class element, so it stays outside), a class constructor
reports the whole `class … { … }`. Anything with no source of its own — builtins, a bound
function, a proxy over a callable — keeps the spec's `NativeFunction` form.

### The value model

Every value that is an object in the spec sense owns an ordinary property table.
`values/PropertyTable` holds the data and accessor entries, the insertion-ordered `keyOrder`
that gives spec own-key order, the per-key `PropertyFlags(writable, enumerable, configurable)`,
the symbol-keyed maps and the `extensible` flag; `JsValue` exposes it through `ownProperties()`,
`getProto()`, `setProto()` and `isExtensible()`. Seventeen types hold one, each keeping its
exotic behaviour *in front of* the table (a `JsArray`'s index and `length` handling, a typed
array's canonical numeric indices, `JsGlobalObject`'s fallthrough to the global `Environment`)
and delegating the rest, allocating lazily. `JsArray` splits its exotic behaviour across
`JsArrayLength` (the `length` setter and the three tail-removal strategies) and
`JsArrayProperties` (the own-property protocol and index slot resolution); both are package-private
collaborators over `JsArray`'s own storage fields rather than independent objects, because the
dense list, the sparse map and the index-accessor maps are one invariant. The two callable types
share their metadata forwarding through the `JsCallableProperties` default methods, over the
`CallableMetadata` each one owns. The **primitives keep `ownProperties() == null`** —
that null is what identifies a primitive at every choke point — and `JsProxy` has none either,
since `ProxyDispatch` intercepts ahead of it. Descriptor validation is a single
`ValidateAndApplyPropertyDescriptor` over the table.

Callability and **constructor-ness** are separate bits, as in the spec.
`JsNativeFunction` carries an explicit `[[Construct]]` flag defaulting to false, set by
`markConstructor()` at the sites that install a real constructor; `JsFunction` computes it from
`!arrow && !async && !generator && !method`; `JsProxy` recurses into its target;
`InterpreterUtils.isConstructor` is the single source of truth. The bit is deliberately not
derived from `getPrototype() != null`, because a script can assign `Array.from.prototype = {}`.

### Execution

`Interpreter` tree-walks the AST. `Environment` is the scope chain (function-scoped `var`
hoisting, block-scoped `let`/`const` with a TDZ, `this`/home-class binding, per-iteration loop
bindings, `using` registrations); `Completion` carries control flow (`NORMAL`/`BREAK`/
`CONTINUE`/`RETURN`, labelled); `JsOperators` and `JsCoercion` hold operator semantics and
conversions, including a real `OrdinaryToPrimitive` that consults `[Symbol.toPrimitive]` then
`valueOf`/`toString` by hint. The other **well-known symbol hooks** are wired at their choke
points too: `instanceof` consults a callable `[Symbol.hasInstance]` before the heritage walk,
`Object.prototype.toString` reads a string `[Symbol.toStringTag]`, and the `String` methods
`split`/`replace`/`replaceAll`/`match`/`search` delegate to a matching well-known-symbol method
on a plain-object argument.

**Generators and async share one mechanism.** A `Coroutine` runs the body on a JDK virtual
thread and hands control back and forth through a single `ReentrantLock` + `Condition`, so only
one thread ever runs (single-threaded JS semantics, no shared-state races). An `EventLoop` owns
the microtask queue (promise reactions, coroutine resumes), a real-time due-time-ordered timer
queue for `setTimeout`/`setInterval`, and an async-job mechanism that keeps the loop alive while
an off-thread host call (a `fetch`) is outstanding and settles it back on the loop thread.
Microtasks always run before the earliest-due timer, the wait is deadline-aware (a timer due
past the sandbox budget aborts rather than over-sleeping), and `SimpleJs.run` drains to
quiescence, applies the result contract, drains again, then cancels anything still suspended.
Unhandled rejections are reported to the console sink at end-of-drain.

**Intrinsic prototypes are realm-scoped.** `builtins/Intrinsics` builds one set of prototype
objects per `Interpreter` — deliberately not static, since a shared `Array.prototype` would let
one tenant's monkey-patch poison every later script in the JVM. Each prototype's entries are
delegating wrappers (a `JsNativeFunction` that coerces the receiver and re-dispatches into the
untouched family class), installed non-enumerable but writable and configurable, so enumeration
and `JSON.stringify` are unaffected while `Array.prototype.join = f` and
`delete Array.prototype.push` both work. `MemberEvaluator` handles each value type's own state
first (index, `length`, `size`, regex flags) and then walks `Intrinsics.protoFor(target)` up to
`Object.prototype`. `Array.prototype` is fully generic in the spec's shape (`ToObject`/
`ToLength` + lazy per-index `HasProperty`/`Get`/`Set`/`Delete`), primitive wrappers are real
objects carrying the primitive in a slot, user classes own real `prototype` objects, and builtin
**subclassing** works via `JsClass.nativeSuperClass` (a builtin with internal state is kept as
the instance's wrapped primitive, which the wrappers unwrap). `Function` exists so
`Function.prototype` and `instanceof Function` work, but calling it throws — no runtime code
generation.

### The regex engine

`internal/regex/` implements ECMA-262 Pattern Semantics directly rather than translating to
`java.util.regex`, which cannot express per-iteration capture reset, unbounded lookbehind, or
code-unit-vs-code-point stepping without `/u`. `RegexParser` is a recursive-descent
parser/compiler (Annex B leniency, `u`/`v` strictness, modifier groups and `v`-mode set notation
are early errors raised here) producing an `RxNode` AST; `RegexMatcher` executes it by
continuation-passing backtracking, mirroring the spec's own Matcher/Continuation model. Literals
are validated at *parse* time, so a bad pattern is a `SyntaxError` before evaluation.

Case-insensitivity is resolved once at compile time: `CaseFold` precomputes a case-equivalence
closure by union-find, gated so a non-ASCII code point never folds into ASCII without `/u`.
Negation order matters and follows the spec — a property escape negates before widening, a
`[^…]` class widens its positive content and negates last. Two performance safeguards exist
because a real backtracking engine is exposed to blowups `java.util.regex`'s optimizations hide:
alternation is a loop rather than a Java frame per branch (a `\p{RGI_Emoji}` class compiles to
thousands of alternatives), and failed `(position, min, max)` repeat attempts are memoized.
Neither stops a nested quantifier such as `(x+x+)+y` from backtracking exponentially, so the
matcher also takes a `RegexStepBudget`, a callback charged once per start position, once per
`match` call, once per simple term a sequence consumes, once per candidate count a simple quantifier
backtracks through, and once per 64 characters a literal or a backreference compares (the compare
runs in place over the input, with no substring copy). The 4-argument
`RegexMatcher.exec` is unlimited and is for trusted patterns only; every caller that runs an
untrusted pattern or input passes a budget. `RegExp` in scripts passes one that calls
`InterpreterOps.tick()` every 64 steps (see *Sandbox and resource limits*), and the JSON Schema
validator passes its per-document evaluation budget (`schemaValidationBudget`, refused with
`400-23`). The budget throws its caller's own exception through the continuations; the matcher
holds no state beyond one `exec`, so nothing needs unwinding.

Unicode property escapes resolve a name to a `CodePointSet` using `java.util.regex.Pattern` as a
one-time per-property **oracle** (compiled once, every code point tested, cached) — never as the
matching engine. Properties *of strings* (`\p{RGI_Emoji}` and siblings) have no JDK data at all
and are expanded from `src/main/resources/simplejs/emoji-sequences.txt`, which is ours and
pinned to Unicode 17.0. This is why the build JDK is pinned at 27 or newer: the UCD comes from
the JDK, and two JDK versions would make `\p{…}` answer differently on two nodes of one cluster.

### Numbers

`ToString(Number)` is spec-exact and **shared with EJson**: one `ejson/internal/NumberFormatter`
backs both `NumberTypeAdapter` (document text and wire responses) and `JsCoercion`. So
`String(1e21)` is `"1e+21"`, `String(1e20)` is the full decimal expansion, and a document field
of `1e20` is persisted exactly instead of saturating at `Long.MAX_VALUE`. Integer conversions
(`|`, `>>>`, `Math.imul`, typed-array and `DataView` writes) use the spec's modulo-2³²
`ToInt32`/`ToUint32` rather than a saturating cast.

### `crypto`

A namespace object like `Math`/`JSON`, so a stored procedure can mint ids and hash content
without reaching for `Math.random()`: `crypto.randomUUID()` (a `SecureRandom`-backed v4 UUID),
`crypto.getRandomValues(typedArray)` (filled in place) and
`crypto.hash(algorithm, data[, encoding])` (`sha-1`/`sha-256`/`sha-512` over a string or
`Uint8Array`, hex or base64). Two deliberate divergences from WHATWG: `hash` is **synchronous**
and Node-shaped rather than the promise-returning `crypto.subtle.digest` (the digest is CPU-bound
and in-process, and a procedure computing a content hash wants a value, not a microtask), and a
request over 65,536 bytes is a `RangeError` rather than a `QuotaExceededError`, which would need
a `DOMException` the engine does not have.

## The host contract

`HostBindings` is the whole seam between the engine and its embedder. Every member has a safe
default, so an embedding grants capabilities by supplying them rather than by opting out.

| Member | Default | What it grants |
|---|---|---|
| `args()` | empty | the `import args from "args"` payload |
| `database()` | `null` | the `db` module (`DatabaseAccess`) |
| `network()` | `null` | `fetch` (`NetworkAccess`) |
| `moduleResolver()` | `null` | specifiers beyond the built-ins (`ModuleResolver`) |
| `console()` | captured only | where `console.log` is teed |
| `cancellation()` | `null` = never cancelled | out-of-band stop (`CancellationToken`) |
| `limits()` | permissive | the sandbox (`ResourceLimits`) |
| `timeZone()` / `locale()` | the JVM's | `Date`, `Temporal.Now`, `toLocaleString`, `localeCompare` |

`host/DatabaseHostBindings` is the database's implementation: it pins the time zone and locale
from `scriptTimeZone`/`scriptLocale` (so every node answers alike), builds `ResourceLimits` from
configuration — never from the request — and supplies `EnforcingDatabaseAccess`,
`ProcedureModuleResolver`, a real `CancellationToken` and, when `scriptFetchEnabled`, a shared
`JdkNetworkAccess`.

### Sandbox and resource limits

`Interpreter.tick()` runs at loop back-edges and call entries and is where the instruction
budget, the wall-clock deadline and the cancellation token are all checked; a recursion depth cap
is enforced per call. Breaching any of them throws a `ScriptAbortException`, which user
`try`/`catch` cannot intercept and which skips `finally` and `using` disposal — so a script
cannot trap its own timeout in a `finally { while (true) {} }`.

Regex matching is inside that boundary too. A single `RegExp.prototype.exec` (and so `test`,
`match`, `matchAll`, `replace`, `split` and `search`, which all reach it) used to be one Java call
that never returned control to the interpreter, so a catastrophic pattern ran past
`scriptTimeoutMs`, the instruction budget and cancellation alike. `RegexExec.builtinExec` now hands
the matcher a budget that ticks once every 64 matcher steps, so the budget, the deadline and the
cancellation token all reach inside one `exec`, and a runaway pattern ends with `400-11`, `408-1`
or `408-2` like any runaway loop. The side effect: regex work counts against
`scriptInstructionBudget` (and the before-hook, pipeline and trigger budgets) at one instruction
per 64 matcher steps. Regex semantics are unchanged.

The **allocation budget** (`scriptMaxMemoryBytes`) is a per-run cumulative total of the
allocations proportional to a script-supplied length or to input size — a `repeat` result, a
dense array, a typed array, a `join`, a `JSON` payload, a `structuredClone` node — and of what
the script pulls out of the database (`DbModule` charges the estimated size of every document a
host call materialises, per element *inside* `aggregate`'s conversion loop, so a runaway read
aborts partway). It is deliberately **not** a live-heap cap: `ThreadMXBean` does not track
virtual threads, and a `Cleaner`-based scheme would make the limit GC-timing dependent. The
dividing line is that `tick()` already bounds allocation costing at least one instruction per
unit, so the budget only covers what is O(N) in a single instruction. String `+` is charged its
appended delta, not the combined result (charging the result would make `s += "x"` quadratic,
while `s = s + s` — the case `tick()` cannot see — has a delta equal to the whole string).
`db.cursor` batches are the one charge credited back, at a fixed deterministic release point.
`OutOfMemoryError`/`StackOverflowError` are caught at the `SimpleJs.run` boundary as a last
resort and reported as `ScriptMemoryError`.

Outputs a script keeps by construction are charged in full: every piece `split` produces, every
node `JSON.stringify` and the host-bound result conversion build (released once the text or the
host value exists, so the charge is a peak guard), every element `flat` writes, the text of a
`fetch` response, and `toHex`/`toBase64` output. A `RegExp` compiled at run time charges its parse
for the duration of the parse, and the process-wide program cache keeps only patterns of at most
4096 characters, evicting least recently used ones past one million cached source characters.
Every native step that allocates in proportion to a script-supplied length charges before it
allocates, computing the size in `long` so it cannot wrap to zero: growing an array through
`length` or an index write past its end (or `Object.defineProperty` on either), the argument list
`apply`, `Reflect.apply` and `Reflect.construct` build (credited back when the call returns),
`Vector.from`, every `Iterator.prototype.windows` copy, `ArrayBuffer.prototype.slice` (including
`slice(-Infinity)`), the boxed copies typed-array `sort`, `toSorted`, `toReversed` and `set` make,
`String.raw`, the indentation and per-occurrence string text `JSON.stringify` renders (at its
escaped width, so a string of control characters is charged six characters per character), the
worst-case expansion of `normalize` (held only while it runs), every value `JSON.parse` builds
(32 bytes per node, as for any array element), the holes a builtin pads when it writes past the
dense end of an array (`fill`, `copyWithin`, `splice` and every other `ArrayLike` write, the
`CreateDataProperty` writes of `map`, `concat`, `Array.from` and friends, and a `Reflect.set` whose
receiver is an array), the array-like a Proxy `ownKeys` trap returns (refused past
`scriptMaxArrayLength`, then charged and paced as it is copied, stopping at the first key that is
not a string or symbol), the text of `encodeURI`/`encodeURIComponent`/`escape` (counted in a
first pass, so the output buffer is sized and charged before it is filled), and the decimal,
radix or grouped digits of a BigInt converted to text (`toString`, `toLocaleString`, `String(x)`,
a template substitution) once it is wider than 64 bits. A global `replace`/`replaceAll` with a
replacement string over the built-in `exec` appends each match to the output as it finds it and
keeps no match list; a replacer function or a custom `exec` keeps the specification's two passes
(every `exec` call before any result is read) and charges each retained match. An array-like whose `length` is
above **`scriptMaxArrayLength`** (default 16 777 216) is refused before anything is allocated, as
an untrappable `ScriptArrayTooLargeError` (`400-30`): `apply`/`Reflect.apply`/`Reflect.construct`
argument lists, `Vector.from` and `Array.fromAsync`. The event loop also ticks once per microtask,
so a promise chain that never yields to a timer (`Array.fromAsync` over a huge array-like) still
meets the deadline. A `db.*` write payload is charged for every string and key occurrence it
contains, not once per distinct string, so an object that shares one large string across many
fields is refused on the memory budget before it reaches the collection lock; the charge is
credited back once the payload is converted.

`BigInt` parsing refuses more than `BigIntLimits.MAX_PARSE_DIGITS` (65 536) digits with a
`RangeError` (the JDK's decimal parse is quadratic); a longer non-decimal `Number("0x…")` literal
and a longer `parseInt` digit run evaluate to `Infinity` (or the value of the digits left after
leading zeros) without being parsed. Every promise (96 bytes) and every generator or `async`
function coroutine (2Kb) is charged when it is created, since the run keeps them until it ends;
a queued microtask (64 bytes) and a pending timer (96 bytes) are charged while they wait and
released when they run or are cleared, so a backlog is bounded without taxing a long `await`
loop. Spreading an array charges the slots its storage does not back, so `[...a]` over
`a.length = 2 ** 32 - 1` fails on the budget instead of materialising four billion holes.

What the static model still cannot see — many retained copies of a large string (`big + i`,
`big.slice(1)`), a heap full of suspended coroutines — is caught by the **heap ceiling**
(`scriptHeapCeilingPercent`, default `75`, `host/ScriptHeapGuard`). Every charge of at least
64Kb, every string result of at least 32K characters and every 16 384th instruction check the
JVM's used heap against that percentage of its maximum; past it, and if the live set measured
after the last garbage collection is above half the ceiling (so a heap merely full of garbage is
not mistaken for retention), the script is stopped with `ScriptMemoryError`. Charging every copy
instead would make the accumulate-and-slice idioms the delta charge exists for quadratic. The
server switches the ceiling on at startup (`Main` calls `ScriptHeapGuard.setCeilingPercent`); the
engine itself reads no configuration, so SimpleJS embedded on its own (the test262 worker) runs
without it.

**Deadline granularity.** `tick()` checks the wall clock every 64 instructions, and every
weighted `tick(n)` checks it unconditionally. Natives whose cost grows with their input pay a
weighted tick before or right after the work (`InterpreterOps.paceWork`, one instruction per 1024
units): `localeCompare`, `normalize`, case mapping, `trim`, `slice`/`substring`/`substr` on large
strings, `BigInt.prototype.toString` (by bit length squared), `crypto.hash`, `toHex`/`toBase64`
and a regex compile. A single such call can still overrun the deadline by its own duration, never
by more. The regex matcher charges one step for every 64 characters a simple quantifier scans or a literal or
backreference compares, so
a pattern that rescans a long input from every start position (`/a{1000000}/` against 999 999
`a`s) runs into the budget instead of spending minutes between deadline samples. A literal string
search (`indexOf`, `lastIndexOf`, `includes`, `split`, `replace`, `replaceAll`) whose worst case
exceeds 2^24 character comparisons runs as a linear-time KMP scan (`string/StringSearch`) that
ticks every 1024 characters, so a 4M-character haystack and a 4K-character near-miss needle
cannot hold the thread for minutes between deadline samples; smaller searches keep the JDK's
intrinsic. For-in and `Object.keys`/`values`/`entries` over an array walk only its populated
indices (`JsArray.populatedIndices`), so a sparse `length` near 2^32 costs nothing.

**Coroutine teardown.** At the end of a run every suspended generator or `async` function is
cancelled once; its `finally` blocks run as the language requires, but a `yield` or `await`
reached after the cancellation throws immediately instead of suspending, so `finally { yield }`
can no longer park a virtual thread (and the whole realm it references) forever. A coroutine that
such a `finally` starts is registered while the teardown walks the list and is cancelled in the
same pass. Per-realm engine state (the `parseInt`/`parseFloat` functions shared with `Number`, the
iterator-helper prototypes, the stepping state of built-in iterators) lives on the realm's own
objects (`JsObject` internal slot), never in a static map, so a finished run's realm is
unreachable once its result is converted. A parser
`StackOverflowError` on a deeply nested source is answered as a syntax error (`400-9` for
`RUN_SCRIPT`, `400-13` for `SAVE_PROCEDURE`) without logging a stack trace.

**Native loops and recursion.** Work a builtin does in Java, not in interpreted code, is charged
to the same machinery. A native loop over an array-like paces through `ArrayLike.has`/`get`/
`delete`: every 1024th index calls `InterpreterOps.tick(1024)`, which takes a whole block from the
instruction budget and checks the cancellation token and the deadline on every call — so a sparse
`new Array(2 ** 32 - 1).indexOf(x)` ends with `400-11` (or `408-1`/`408-2`) instead of scanning
four billion holes, while a scan shorter than 1024 elements costs nothing extra. A loop that
bypasses `ArrayLike` (the typed-array scans, `fill`, `copyWithin`, `join`) calls
`InterpreterOps.pace` itself. Native recursion over script data — `JSON.parse` and its reviver,
`JSON.stringify`, `structuredClone`, `flat`, `join`/`String()` of nested arrays — is bounded by
`NativeDepth.MAX = 256` levels, and every native call counts one level, so native-to-native
recursion through `toString`/`toJSON` is bounded without touching each builtin; past it a
**catchable** `RangeError: Maximum nesting depth exceeded` is thrown (256 levels fit a
virtual-thread stack under the deepest `scriptMaxDepth` call stack; a test pins it). A `db.*`
call (and each `db.cursor` batch) also needs `NativeDepth.DB_CALL_RESERVE` (32) levels of headroom
and is refused with that same `RangeError` before it touches a lock, because a stack overflow inside
a host lock acquire or release would leave the lock held for every other caller. BigInt
results are sized **before** they are computed (`internal/BigIntLimits`): a result over 2^24 bits
(2 MiB) throws a catchable `RangeError: Maximum BigInt size exceeded`, a result above 64 bits is
charged to `scriptMaxMemoryBytes`, and an exponent or shift count beyond `int` is that same
RangeError rather than an internal error. That covers every operator, not only the growing ones:
`~`, unary `-`, `/`, `%`, `&`, `|`, `^`, `>>`, `++` and `--` keep their operand's size, so each is
sized as its widest operand plus one bit, the same estimate as `+`. A `JSON.stringify` replacer
array is de-duplicated through an insertion-ordered set and paces its build loop, so 300 000 keys
cost one pass instead of a quadratic scan that ignored the deadline. Finally, an `Error` raised on a coroutine thread (every
script body, generator and async function runs on one) is relayed to the driver and rethrown
before any generator or promise observer sees it, so a `StackOverflowError` deep inside an async
function ends the run as `400-12` instead of hanging it with its admission permit held.

Per-run budgets bound **one** run; `ops/ScriptAdmission` bounds their sum. It first takes this
caller's `maxConcurrentScriptsPerUser` (default 4) and this database's
`maxConcurrentScriptsPerDatabase` (default 8) slice, without waiting, and only then caps
client-initiated runs at `maxConcurrentScripts` (admitting after a bounded `scriptQueueWaitMs`
wait, else `503-6`): a tenant already at its ceiling is told at once instead of queueing for a
node-wide permit it could not use. The returned `Permit` records which pools it took, so a
node-wide refusal releases the slices. Triggers and schedules take the two slices as their
definer (`acquireSlices`), not the node-wide permit: they are already bounded by their own worker
pools, but without the slices one tenant's automation could hold every interpreter its users'
scripts compete for. A trigger refused a slice is re-queued after a backoff without consuming an
attempt (dead-lettered only once `triggerRunRetentionMs` has passed since it fired), so it is
delayed, never dropped; a scheduled run refused one skips that occurrence. Before hooks stay
exempt — they run inline under the write lock, where a refusal would be a failed write. The
node-wide ceiling on concurrent interpreters is therefore
`maxConcurrentScripts + triggerThreads + scheduleThreads`, and that sum times
`scriptMaxMemoryBytes` is the worst-case script heap to size `-Xmx` against — additive with
`maxMemory` and the metadata cache budgets.

`fetch` is bounded by `HostAllowlist` (an exact host, a `*.example.com` sub-domain wildcard, or a
bare `*`; an **empty** list denies everything even with the switch on), a response size cap and a
timeout, and runs off the interpreter thread as an `EventLoop` async job. It reaches
`RUN_SCRIPT`, `CALL_PROCEDURE`, triggers and schedules, and deliberately not pipeline scripts or
before-write hooks, which hold collection locks where a blocking egress call is a stall.

- **One parser.** The host the allowlist checks and the host connected to both come from
  `URI.getHost()` (`host/FetchTarget`); only `http`/`https` URLs with a host are accepted, anything
  else is "fetch: host not allowed".
- **Public addresses only, checked where it connects.** `JdkNetworkAccess` resolves the host once,
  requires every address to pass `PublicAddressPolicy` (no loopback, private, link-local, CGNAT,
  multicast, reserved, unique-local, or IPv6 form embedding such an IPv4 — mapped, compatible,
  NAT64, 6to4) and pins exactly those addresses in `PinnedResolution` for the duration of the
  request. `PinningResolverProvider`, a JDK `InetAddressResolverProvider` registered through
  `META-INF/services`, answers the `HttpClient`'s own lookup from the pin, so a DNS answer that
  changes between the check and the connect (rebinding) cannot redirect it; unpinned names
  delegate to the JDK's built-in resolver, so cluster and peer resolution are unchanged. The
  Host header, SNI and certificate checks still use the URL's host. Only an **exact** allowlist
  entry equal to the host (`10.0.0.5`, `localhost`) lets it reach a private address; `*` and
  `*.example.com` never do. Redirects are never followed (the client's policy is `NEVER`).
- **Bounded body.** The body is read as a stream and abandoned the moment it passes
  `scriptFetchMaxResponseBytes` (or at once when `Content-Length` already says so), counted in
  bytes, so an endless response cannot exhaust the heap.
- **Bounded concurrency.** One run may have at most `scriptFetchMaxConcurrent` (default 8) fetches
  in flight; one more rejects with "fetch: too many concurrent requests" until an earlier one
  settles.
- **No connect detail.** Every network failure other than a timeout, a blocked host or an
  oversized body rejects with the bare "fetch failed"; the cause goes to the server log, so a
  script cannot use error text to scan ports. A fast refusal and a timeout still differ in
  timing for hosts the allowlist permits.

`strictScriptGoal` selects the spec's Script goal. It defaults to `false`, which is what the
database host uses — the result contract deliberately allows a top-level `return`, and
`import.meta`, `new.target` in global code and a top-level `using` are tolerated. Only
`Test262Worker` sets it true, making each of those an early `SyntaxError`.

### Module loading

Every specifier — static `import`, `import()`, `export … from` — funnels through the
interpreter's **per-run module registry** (`internal/interpreter/ModuleRegistry`), keyed by an
opaque module id. That gives three properties: a module evaluates at most once per run, a module
that threw stays failed and rethrows the original error rather than re-running its side effects,
and an import of a still-evaluating id is a **cycle** (a catchable
`Error("Circular import of module '…'")`). The registry is never shared between runs, because an
evaluated module holds mutable state.

An imported module is evaluated **in the importer's realm** — same `Interpreter`, `Intrinsics`,
`EventLoop`, coroutine and thread, same instruction counter and deadline, in a fresh module
`Environment`. That is load-bearing, not incidental: a second interpreter would give the imported
module prototypes the importer cannot match (`e instanceof TypeError` false), an event loop
nothing drains, a thread that trips the transaction affinity assertion, and a fresh budget a
throwing import could farm indefinitely. Nesting is capped by `ResourceLimits.maxModuleDepth`
(default 16) purely as a Java-recursion guard.

Three built-ins are seeded into the registry: `args` (the request payload), `db` (the
`DatabaseAccess` surface) and `script` (`importText(source[, moduleId])`, which runs a **string**
as a module, content-addressing its id by SHA-256; gated off by default behind
`scriptTextImportEnabled`). Top-level `await` inside imported text works wherever `importText` is called. Called from
an async function or module code — directly, through `.call`/`.apply`/`bind`/`Reflect.apply`, or as a callback a
builtin invokes synchronously (`map`, `Array.from`, a getter, a `Proxy`) — it suspends that caller, the coroutine the
interpreter is running. With no async caller (a plain function, a generator, a `.then` handler, a timer callback)
the text is evaluated in a coroutine of its own and the event loop is driven until it finishes, so other queued
jobs can run before `importText` returns; text that awaits something that never settles throws `Error: Module '…'
awaited something that never settled` instead of hanging. A default import of a built-in binds the built-in object itself,
while a default import of a real module binds its `default` export — `ModuleEvaluator` tags each
resolution accordingly. Only `export`ed bindings are importable, which is the one trap worth
knowing: a procedure written for `CALL_PROCEDURE` with a top-level `return` imports as
`{ default: undefined }`.

Everything else is the host's decision through `host/ModuleResolver`. The database host resolves
**`procedures/<name>`** (`ProcedureModuleResolver`, gated by `scriptProcedureImportEnabled`,
default on), so a library *is* a stored procedure — managed by the operations that already exist,
with nothing new persisted or replicated. The namespace is flat and scope-restricted (the name
must pass the wire's procedure-name rule, `NameValidations.isValidProcedureName`: 3-64 letters,
digits, `_` or `-`, so `/`, `..`, `\`, NUL and over-long names never reach the file layer; only
the run's own database is searched), a missing or disabled procedure
answers `Cannot find module`, and the module id carries the procedure's version so a
`CompiledProcedureCache` entry can be reused across runs. Authority is the **importer's**:
importing widens what code is reachable, never what it may do. Reading the code is a separate
right: when the run's user lacks script `MANAGE` on the database (and is neither its owner nor an
admin), `EnforcingDatabaseAccess.mayReadProcedureSource` is false and the resolver answers
`ResolvedModule.withHiddenSource`, which `ModuleEvaluator` parses with
`Parser.parseWithoutSourceText` (no spans, never the shared cached parse), so `toString()` of an
imported function or class is the `NativeFunction` stub — the same rule `LIST_PROCEDURES`
applies to `includeSource`. `SAVE_PROCEDURE` walks the
compiled program's static specifiers and refuses an unresolvable `procedures/` target with
`400-18`, so a typo reaches whoever installs the procedure — which means install order matters.

### The `db` module

`db` exposes `findById`, `aggregate`, `cursor`, `save`, `bulkSave`, `delete`,
`listCollections`, `listDatabases`, `transaction` and `name`. It is deliberately **read+write
and single-database**: no DDL, no index management, no user operations, and no access to a
database other than the run's scope. Opening DDL to scripts would put `CREATE_INDEX`/
`DROP_COLLECTION` behind a trigger's definer rights and would race the admin coordinator's DDL
serialization; a migration is a client driving DDL over the wire and calling a script for the
data half.

`db.listDatabases()` answers the run's scope without a request when the run is scoped. An unscoped
run (an ad-hoc `RUN_SCRIPT`) dispatches `LIST_DATABASES` as its acting user, so it gets the same
filtered answer the wire gives that user: every database for an admin, otherwise only the databases
the user owns or holds a database, collection or script grant on.

**Failure contract**: every method throws a catchable JS `Error` (built with the script's own
realm `Error.prototype`) whenever the underlying `OperationResponse` is not OK — a denial, a
schema violation, an entry-too-large, a cluster rejection, an internal error. Only genuine
absence stays a value: `findById` answers `null`, `aggregate` answers `[]`, and `delete` of an
absent id is a no-op. A collection that is not registered is not absence: reads of it throw
(`404-11`) exactly as writes do, and so does a JOIN into one. A call that omits a required argument, or passes one of the wrong shape, is a
catchable `TypeError` too — `db.save` needs a document and `db.aggregate`, `db.bulkSave` and
`db.cursor` each need an array, and every database name, collection name and id must be a string:
nothing is coerced, so `db.delete(db.name, "c")` or `db.findById(db.name, "c", 5)` throws rather
than addressing the document whose `_id` is `"undefined"` or `"5"`.

**`db.cursor(database, collection, pipeline, options)`** walks a pipeline one batch at a time so
a script can read a collection larger than its memory budget. Each batch is an ordinary
`AGGREGATE` with `SKIP`/`LIMIT` appended to a *copy* of the caller's pipeline, so it is
authorized, schema-checked and cluster-routed exactly like a hand-written `db.aggregate` and adds
no cluster surface at all. A pipeline that does not already end in a defined order is paged with
a `SORT` on `_id` appended before `SKIP`/`LIMIT`, because an unordered source enumerates differently
warm and cold and an eviction between batches would otherwise repeat or skip rows; rows with no
`_id` (`GROUP_BY` output) keep their stream position under SORT's own fallback. A `LIMIT` or `SKIP`
the caller's own pipeline applies to unordered documents gets a `SORT` on `_id` inserted before it
too, so `[LIMIT 100]` pages the first 100 documents by `_id` rather than a subset that differs warm
and cold. It is still **not**
a snapshot (a write landing between batches can show a document twice or not at all), and **not**
stateful server-side (abandoning it holds nothing to release).

**`db.transaction(fn)`** runs `fn` in a transaction, committing on return and rolling back on
throw. The scoped-callback form is what makes it safe: a transaction holds each written
collection's write lock across calls and `ResourceLocking.releaseWrite` is thread-owned, so the
callback **may not suspend** — an async function, a generator and a returned promise are all
rejected with a `TypeError`, and `EnforcingDatabaseAccess` pins the session to the opening
thread. Because the rollback lives in Java, a sandbox abort still rolls back and releases the
locks. Three limitations: `listCollections` inside a transaction throws a catchable `Error`
(`409-6`, listing is refused while a transaction is open), and `listDatabases` answers the run's
scope without a request when the run is scoped and otherwise throws the same error; under clustering the 2PC round trips block the thread owning the
locks; and the three control ops skip `AuthorizationChecker` (they carry nothing to authorize —
each buffered write is still authorized on its own request).

`db.bulkSave` returns `{inserted, updated}` rather than the saved documents, since re-reading N
documents would defeat the batch.

### EJson custom types

The four custom types the database stores cross the boundary as real value types rather than
vanishing, so a `geo` or `vector` field is readable, mutable and constructable from a script.
Each is a non-enumerable global constructor with a realm-scoped prototype and a `from(value)`
static that accepts an instance, the wire string, a property bag (or, for `Vector`, an array).

| Global | Wraps | Accessors | Methods |
|---|---|---|---|
| `Geo` | `#geo(lat,lng)` | `lat`, `lng`, `geoHash` | `toString`, `toJSON` |
| `Vector` | `#vector(v0,…,vn)` | `length`, `simHash` | `at`, `toArray`, `toString`, `toJSON` |
| `DbDateTime` | `#datetime(…)` | `year`…`second` | `toString`, `toJSON`, `toTemporal` |
| `DbTime` | `#time(…)` | `hour`, `minute`, `second` | `toString`, `toJSON`, `toTemporal` |

`DateTime`/`Time` carry the `Db` prefix because the bare names collide with library code and
future proposals; `toTemporal()` bridges to `Temporal.PlainDateTime`/`PlainTime`, so the calendar
arithmetic is reachable from a stored field without a second implementation. `EJsonInterop`
emits real `JsonGeo`/`JsonVector`/… values, so a document saved from a script keeps the type the
storage and index layers already understand; a custom type registered later with no value type
here degrades to its wire text rather than silently vanishing. A custom value **read from a
document is written back with its original spelling**: the value type keeps the `JsonCustom` it
came from, so `#geo(45,-122)` passing untouched through a before hook or a `db.findById` →
`db.save` round trip is stored as `#geo(45,-122)`, not re-derived as `#geo(45.0,-122.0)`. Only a
value the script builds itself (`new Geo(...)`, `Geo.from(...)`) takes the derived spelling.
`Geo.from('#geo(...)')` applies the same range check as the constructor, so a `NaN` coordinate is a
`RangeError` either way, and a `'#geo(NaN,…)'` string is refused when it is promoted into a document.

A **plain string in custom wire format is promoted on the way into a document**, so
`'#geo(1,2)'` stores the same custom value as the identical bytes arriving over the wire and is
indexed in the same family. `Geo.from('#geo(1,2)')` is the same point in the same family, but as a
value the script built it takes the derived spelling `#geo(1.0,2.0)`. The promotion
keeps the raw text verbatim — `#geo(1,2)` is stored as `#geo(1,2)`, not normalised to
`#geo(1.0,2.0)` — so it changes the value's *type* and never its bytes. An unregistered or
malformed one (`#nosuch(1)`, `#geo(bad)`) fails the write with a `TypeError` naming the member,
exactly as the wire path already refuses the identical document at parse time. `JSON.stringify`
is deliberately unaffected: a custom-shaped string stringifies as the string it is.

Nothing on disk needs migrating, because the promotion never changed a stored byte. A collection
that a script wrote a custom value into *before* this behaviour existed does hold that value in
the wrong index family, though, and one `REINDEX` of the collection moves it into the right one.

### What a run reports back

- **Result contract**: a top-level `return` if the module runs one, else `export default`, else
  an object of the named exports, else `undefined`. A promise in that position is **awaited** —
  a rejection becomes the script error, and one still pending after the drain fails the run
  (`400-20`) rather than quietly contributing JSON `null`. The conversion happens *inside* the
  interpreter's lifetime, so an accessor-valued property is read through its getter and the
  getter's work is charged to the run's budgets. The same holds for a before-hook replacement, a
  MAP `SCRIPT` value and a REDUCE accumulator, which convert under their session's own stack
  capture. An array is read by its length the way `JSON.stringify` reads it — an index getter
  through the getter, a hole or a sparse slot as `null` — and a `Proxy` through its `get` and
  `ownKeys` traps; an array longer than 2^24 elements fails with a `TypeError`, since no document
  could hold it. The converted result is measured against
  `scriptMaxResultBytes` (`400-15`); a trigger passes `-1`, since its result is discarded.
  A **non-finite number cannot cross into a run result or a document**: `Infinity`, `-Infinity`
  and `NaN` fail the conversion with a `TypeError`, because the engine's own reader cannot parse
  the token `NumberFormatter` spells for them. `JSON.stringify` keeps its own ECMAScript-mandated
  answer of `null` for the same values.

  The pipeline's three script surfaces differ, and the rule is whether the result is **stored** or
  only **tested**. A `MAP` value operator and a `REDUCE` accumulator are stored, so a non-finite
  result becomes `null`, matching what the arithmetic operators already answer. A before-write hook
  refuses with a `TypeError`. A `FILTER` script predicate and a `MAP` script condition are only
  tested, so the result never becomes an EJson value at all: truthiness is decided inside the
  interpreter under ordinary JavaScript rules, which means `Infinity` and `-Infinity` **keep** a
  document while `NaN`, `0`, `-0`, `''`, `null` and `undefined` drop it.
- **Console output** is captured on **every** exit path — value, throw, syntax error, abort — as
  a ring buffer keeping the newest `maxLogLines` (a longer line is clipped at
  `maxLogLineChars`, both setting a `logsTruncated` flag), and teed to the host sink when one
  was supplied.
- **Metrics** (`ScriptRunMetrics`): instructions executed, peak bytes held, `db` operations
  issued and wall-clock duration, each beside its budget. They are written in the interpreter's
  own `finally`, so an aborted run still reports what it burned. `peakMemoryBytes` is a
  high-water mark because a credited `db.cursor` batch would otherwise erase it, and a
  straight-line script reports `instructions: 0` because it reaches no `tick()`.
- **Call stacks**: a thrown error carries real frames, so an unattended trigger names where it
  broke rather than only what broke.

  ```
  TypeError: Cannot read properties of null (reading 'total')
      at applyRule (procedures/rules:12:7)
      at onWrite (procedures/audit:31:14)
      at main:4:1
  ```

  Frames are return addresses kept by `internal/interpreter/CallStack` (pushed and popped beside
  the existing depth counter, so already bounded by `scriptMaxDepth`), with line and column
  written per statement from positions the parser stamps. The trace is captured **where the error
  is raised**, not when it is reported — the JS error object is built at the catch site, after
  every frame has unwound. A JS `Error` object takes its frames when it is constructed, from the
  stack supplier its realm's `Intrinsics` holds; an engine exception (`TypeError`, `RangeError`,
  …) is stamped by `CallStack.stamp` at the first frame boundary it crosses — the statement
  dispatch, a native or JS call before its frame pops, or a top-level session entry — so the
  innermost boundary records the frames the throw saw, before any JS `catch` or `finally` in that
  frame runs. Nothing is thread-scoped: the interpreter's own stack object is reached directly, so
  a coroutine's virtual thread and a nested interpreter each see their own frames. Each coroutine
  swaps in its own stack segment per resumption, so a suspended
  generator's frame does not appear in its consumer's trace, and a frame is labelled with the
  module the function was *written* in. Sandbox aborts deliberately capture nothing: a timeout is
  not a program error.
- **Cancellation** is cooperative: `CancellationToken` is polled at the same points as the other
  aborts plus the event loop's two park loops (capped at a 50 ms slice, so a script parked on a
  30-second timer notices now). A run blocked inside a host call ends at the next tick after it
  returns. `ScriptCancelledException` is a `ScriptAbortException`, so it cannot be trapped, and
  it maps to `408-2` everywhere.

## Database surfaces

Six ways a script runs. The differences are not incidental — each column is a consequence of
where the run sits relative to the write path.

| Surface | Authority | `db` | Budgets | Console | Notes |
|---|---|---|---|---|---|
| `RUN_SCRIPT` | caller | yes | `script*` | returned | ad-hoc; parse cached by source hash |
| `CALL_PROCEDURE` | **invoker** (caller) | yes | `script*` | returned | parse cached by `db\|name\|version`, checked by source hash |
| After trigger | **definer** (installer) | yes | `script*`, `triggerTimeoutMs` | logged | async, exactly-once, retried |
| Before-write hook | recorded, not enforced | **no** | `beforeHook*` | discarded | synchronous, in the write lock, fail-closed |
| Schedule | **definer** | yes | `scheduleTimeoutMs` | logged | at-most-once per due instant under a stable view |
| Pipeline script | the query's caller | **no** | `aggregationScript*` | discarded | one callable per pipeline, per document |

### Ad-hoc scripts and stored procedures

`RUN_SCRIPT` carries `databaseName`, `script` and optional `args`, and the script is **scoped to
that one database**: `EnforcingDatabaseAccess` rejects any request naming another (the reserved
`admin` included, so a script cannot read `admin/users` even when an admin runs it), and the
scope is exposed as `db.name`. Running one requires admin rights, database ownership, or a
per-database `scriptPermissions` grant — and that is deliberately a *separate* capability from
what the script may do: every operation it issues is authorized again on its own request, so the
grant never widens the caller's reach.

A stored procedure is a named script; `SAVE_PROCEDURE` compiles it eagerly so a broken body is
refused at save time (`400-13`, with line and column) rather than on somebody else's first call.
Sharing one parse across runs is safe because nothing in the AST is written after parsing — all
runtime state lives in the per-run `Interpreter`/`Environment`/`Intrinsics`.

`scriptPermissions` is a per-database `ScriptPermissionLevel` — `NONE`/`RUN`/`MANAGE`, where
`MANAGE` additionally allows installing procedures, triggers and schedules; admins and database
owners have an implicit `MANAGE`. A legacy boolean reads as `RUN`/`NONE`. For a trigger,
`MANAGE` is bounded by the collection: `SAVE_TRIGGER`, `DELETE_TRIGGER` and `TEST_TRIGGER` also
need `READ_WRITE` on that collection — through an explicit `collectionPermissions` entry when one
exists (the entry alone decides), otherwise through the database grant — and answer `403-1`
without it. An after trigger receives every document written to its collection and a before hook
rewrites or vetoes every write there, so `MANAGE` with only `READ` (or no data grant at all) must
not install one. Procedures and schedules consult no collection: a schedule binds none and receives
no document — it runs its procedure with the `args` its installer chose, and every `db.*` call that
procedure makes is authorized again against the definer's own grants.

**Errors** map to: `403-2` scripting disabled, `404-4` unknown database, `400-10` source too
large, `400-9` threw or would not parse, `400-11` budget/depth, `400-12` memory, `400-15` result
too large, `400-20` pending result, `400-30` an array-like past `scriptMaxArrayLength`, `408-1` timeout, `408-2` cancelled, `409-6` inside an open
client transaction. Console output and a stack ride along on every outcome.

### Triggers

A trigger binds a stored procedure to a collection's writes. **After** triggers run
asynchronously once the write has committed; **before** hooks run synchronously inside the write
lock and decide whether the write happens at all.

Both are installed with a **definer**, and an after trigger runs under **definer rights** rather
than the writer's. That is load-bearing: under invoker rights an audit trigger's effect would
depend on who wrote, and would fail for exactly the low-privilege users most worth auditing —
as a WARN in a log the writer never sees. Two consequences: a definer who no longer exists
**disables** the trigger (falling back to the writer would silently reinstate invoker rights, and
falling back to an admin would let *deleting* a user widen a trigger's authority), and a definer
whose permissions are later reduced narrows it. `ops/DefinerAuthority` re-checks the definer every
time an after trigger or a schedule fires — a recovery replay and a `RESOLVE_TRIGGER_RUN` replay
included, since both go through the same dispatch: an after trigger needs script `RUN` on the
database and `READ` on its collection (the documents it is handed are that collection's), a
schedule needs `RUN`. A definer that is gone or lacks them skips the run and **disables** the
definition with a stored `disabledReason`, so a later re-grant (or a re-created user of the same
name) does not silently revive it; its owner re-saves it. The disable is a definition write, so it
is made only where definitions are decided — a standalone node, or the cluster's admin coordinator
with quorum and a completed admin sync, which replicates it like any other definition change. Any
other node still skips every run and leaves the definition as it is. Before hooks are not
re-checked: a hook has no `db` and no `fetch`, so it can hand nothing to its definer.

The definer is always the saving user: no request field can name another one, so a client cannot
install a trigger that runs as someone else. Peers never derive it either — they receive the
coordinator's definition file, definer included, as a versioned admin record.

Limiting who may *install* a trigger is not enough. A trigger, before hook or schedule names its
procedure, and the procedure is looked up **by name when it fires**, so whoever can change that
procedure — or any procedure it imports — changes what runs with the definer's authority.
While such a binding exists, `SAVE_PROCEDURE` (disabling with `enabled:false` included) and
`DELETE_PROCEDURE` of a procedure it reaches answer `403-4` for anyone but the binding's definer, a
database owner or an admin; an unbound procedure, or one bound only by the caller, stays freely
editable. Reach is the transitive import closure, computed lexically from each procedure's source:
static `import`/`export … from` and a dynamic `import('procedures/x')` with a literal specifier
name exact procedures, while a dynamic import with a computed specifier, or any import of the
`script` module (whose `importText` runs arbitrary module text), makes the procedure able to import
*any* procedure in the database — so while a foreign definer's binding reaches one, every name
counts as reached, existing or new. A disabled procedure still counts, because its definer or an
owner may re-enable it. The check runs in the handler under the database's procedures lock — the
lock `SAVE_TRIGGER` and `SAVE_SCHEDULE` also take, so no binding can appear between the check and
the write — and therefore on the cluster's admin coordinator; peers install the result without
re-checking. Not covered, because no foreign authority changes hands: deleting another user's
trigger or schedule (a denial of service at worst), and replacing another user's trigger by name,
which makes the replacer its definer.

**An after trigger runs exactly once, not at least once.** Before an event is queued,
`TriggerRunLog` persists a pending-run record in `admin/trigger_runs`; `TriggerDispatcher` then
runs the procedure inside a transaction whose final buffered op **consumes** that record, so the
run's effects and the evidence that would replay them commit together. On a cluster the record is
node-local, so the consume op always sits in the firing node's own slice: a body whose writes were
all forwarded to other owners still commits through 2PC with this node as a participant, never as a
bare forward that would leave the record behind. `TriggerRunRecovery`
re-queues what is still pending at startup — only the runs pending when the process started,
snapshotted before the executor, the scheduler or the cluster can record new ones, since a run
recorded after that was already queued by whatever recorded it. This rests on single-node commits being crash-atomic
(`ops/TxCommitLog`), and at-least-once would be unusable for the ordinary case of a trigger that
increments a counter. Consequences: a run costs a transaction; an explicit `db.transaction(…)`
inside a trigger is rejected (the run is already transactional); and the guarantee covers
*database effects*, so a replayed run's console output can repeat.

The record is written **before the evidence of the triggering write can be lost**, never after it.
A transaction stages its runs once its ops have applied and before its commit marker is cleared,
under a run id derived from the transaction id, the trigger, the event and the document, so a
startup replay of that commit re-stages the same records instead of adding new ones. Its buffered
ops are deleted only after the marker, so a replay always sees the whole transaction;
the events are queued only after the marker is gone, so a trigger still never sees a transaction
that could roll back. A standalone `SAVE`/`BULK_SAVE`/`DELETE` stages its runs under the collection
lock *before* the write, as `STAGED` records carrying each document's prior version (or "absent").
If the write lands, the in-process event is queued under that run id; if it is refused or fails, the
records are discarded; a partially applied `BULK_SAVE` keeps only what committed. A `STAGED` run
found at startup fires for exactly the documents whose version has changed since it was staged —
every save stamps a fresh version and a delete removes the document — and is otherwise discarded.
`LIST_TRIGGER_RUNS` reports a staged run as `PENDING`.

A failed run is **retried** — the state machine lives on the pending-run record itself, which is
what keeps exactly-once intact (a record still present is still un-applied). An error raised
*after* the run's transaction committed is the exception: the module body is wrapped in that
transaction and the event loop is drained once the wrapper has returned, so a pending timer or a
throwing microtask surfaces as a script error on a run whose effects are already durable and
whose record is already gone. Such a run is recorded as an error and **never re-queued** —
re-running it would apply the effects a second time. A retryable failure
(a script error or a commit failure) increments `attempts` and re-queues after a doubling
backoff up to `triggerMaxAttempts`, after which the record is marked `DEAD` with its payload and
last error kept, for `triggerDeadLetterRetentionMs` from the moment it died. A retry re-reads its documents when it *runs*, not when it is scheduled, so it
sees any write made during the backoff; if they are gone the run is consumed, and if the read
itself fails the run stays pending and is retried (then dead-lettered) like any other failure. A
run evicted by a full trigger queue is dead-lettered too, not dropped. Everything else is terminal
and consumes the record: a missing definer or procedure, exceeded depth, and a **cancellation** —
the one place the exactly-once guarantee is deliberately waived, because an operator cancelling a
runaway trigger wants it stopped. A run is replayed by the node whose data directory holds it,
whatever id that node ran under when it wrote it, so switching `clusterEnabled` on or off does not
orphan a pending run. `LIST_TRIGGER_RUNS`/`RESOLVE_TRIGGER_RUN` are the admin-only operator surface,
fanned out cluster-wide because `admin/trigger_runs` is not replicated.

Two outcomes are neither ordinary retries nor terminal consumes.

**A definition that cannot be *read* is not a definition that was deleted.** `findTrigger` answered
`null` for both, and the caller consumes the pending run on `null` — so a transient I/O failure
reading `{coll}-triggers.json` deleted the only record that would have replayed the run, and an
after trigger for a committed write silently never ran. It is worst at startup, where every run
`TriggerRunRecovery` re-queues is exposed at once, so one brief disk stall could consume all of
them. An unreadable definition now leaves the record `PENDING` and retries with the same doubling
backoff, dead-lettering only once `triggerMaxAttempts` is exhausted. `AdminCache` already refuses
to cache such a failure as an absence (`MetadataReadException`); the dispatcher now agrees.

**A half-applied commit is not a retryable failure.** `TRANSACTION_HALF_APPLIED` (500-33) means the
transaction passed its commit point, applied some of its ops, and deliberately kept both its write
locks and its commit-log marker so recovery can finish the slice. Re-running the body would apply
those ops a second time — the counter-incrementing trigger double-counts, which is precisely what
exactly-once exists to prevent. The run is dead-lettered instead, leaving it visible to
`RESOLVE_TRIGGER_RUN`. `REPLICATION_TIMEOUT` was already special-cased in the same seam; this is
the second status that needs it.
This holds whatever the script itself answers. A body whose commit fenced and that then throws
from a timer or a microtask still surfaces as a script error, and that error used to take the
ordinary retry path, so the dispatcher now checks the fence before it looks at the result. A
script transaction that committed through a single remote owner, which answered `500-33` or
`409-10`, counts as fenced too (the edge keeps that transaction, see
[clustering](clustering.md)), so such a run is dead-lettered rather than retried. For `409-10`,
dead-lettering is the safe direction: the outcome is unknown, and a retry could apply the body
twice.

Triggers are **staged** from `OperationProcessor`'s write handlers and `tx/CommittedOpTriggers`
— never from the write helpers, since a replicated apply reaches those directly and would fire
once per replica. Enqueueing is one map lookup and a queue offer, so it is safe inside the write
lock. `bckg_ops/TriggerExecutor` runs them on **its own** bounded queue and workers, deliberately
not the background index queue: a trigger runs arbitrary user code, and sharing that queue would
let one slow trigger stall field-index maintenance for every collection. On overflow the oldest
queued run is dead-lettered and counted. The queue is bounded by count (`triggerQueueSize`) and by
bytes (`triggerQueueMaxBytes`): a queued `CREATED`/`UPDATED` event keeps only its ids and re-reads
the documents when it runs (a retry always did), so only a `DELETED` event's snapshot, which has
nothing to re-read, takes real space. Dropping a collection or database discards its queued events
and every pending or dead-lettered run record, so a same-name collection created later never
receives the dropped one's documents. A standalone node garbage-collects stranded and expired
dead-lettered runs every minute, not only at startup; a cluster node already does it in its
transaction recovery sweep. A collection holds at most `triggerMaxPerCollection` triggers
(`400-32`). Cascades carry `triggerDepth + 1` on the request itself — a field,
not a `ThreadLocal`, so the bound survives a cluster forward — zeroed for client requests;
`allowCascade` defaults to false.

A transaction fires each trigger for its **net effect**: several writes to one id fire once, a
`DELETE` of an id the same transaction created fires nothing (no one outside it could ever see the
document), and deleting a document that existed before the transaction fires `DELETED` as usual.
A `batch`-mode trigger runs once per collection and event for the whole transaction, deletes
included, with every affected document in `documents`.

**Before-write hooks** are the veto. Returning nothing or `true` accepts the write, a plain
object replaces the document, anything else or a `throw` refuses it (`400-21`); an abort keeps
its own code, so an operator can tell a hook that said no from one that never finished. Every
failure is **fail-closed**. They run on the `openCallable` seam rather than `SimpleJs.run`, which
is the entry point whose call happens on the caller's thread (the write lock is thread-owned) and
which gives **one interpreter per request** — a `BULK_SAVE` of N documents evaluates the body
once and shares one budget across all N invocations (one interpreter for its inserts, one for its
updates). That holds inside a transaction and inside a trigger exactly as outside one: both paths
open their hooks through `BulkBeforeHooks`, so module-level state and the budget behave the same
whichever way the bulk arrived. There is **no `db`**: a re-entrant call from
under a held write lock would take a lock this thread owns, or make a network round trip while a
writer waits; the module resolver is kept so a hook can import shared code, since that takes no
locks. A replacement may not change `_id` (that would relocate the document, turning an update
into an insert elsewhere) and is re-validated against the collection's JSON Schema, which runs at
the *edge*, before the hook. It is also held to the same nesting cap a client request is
(`Globals.MAX_REQUEST_NESTING_DEPTH`). Several hooks chain in ascending name order and the first refusal
stops the chain. Budgets are an order below `RUN_SCRIPT`'s, because a client write is blocked
while one runs. Under clustering a hook runs on the collection's **owner** — the edge forwards
raw request JSON, so an edge-side mutation would be lost in transit.

`TEST_TRIGGER` is the dry run and covers **before** triggers only, which is the whole reason it
could be built: a before hook is a plain synchronous callable, so testing one is the same call
the write path makes, with no locks and nothing written, and the console output is *captured*
(the live path discards it, since a hook runs once per document). For an after trigger the advice
is to put the logic in a procedure, exercise it with `CALL_PROCEDURE`, and make the trigger a
thin import-and-delegate wrapper. The residual gap is real: this exercises the trigger *body*,
not trigger *dispatch*.

An after trigger's failure never reaches the writer — it is logged at WARN with the captured
output and counted in `GET_DATABASE_STATS`, which is the operator's only window into an execution
path no client is waiting on.

### Scheduled procedures

A schedule binds `{cron | intervalMs} → procedure + args`, stored with its database in
`{db}/.schedules/{name}.json` (so `DROP_DATABASE` removes it with no cascade code) and replicated
by the coordinator-serialized admin DDL path. Because the project carries no runtime
dependencies the cron parser is ours (`ops/schedule/CronExpression`, five fields), advancing
**field-wise** rather than minute by minute and giving up after a four-year horizon, so an
unsatisfiable expression such as `0 0 30 2 *` answers `null` instead of spinning. Candidates are
walked as *local* date-times and only then resolved against `scriptTimeZone`, which is what makes
a daily schedule fire once across a DST transition.

Delivery is **at-most-once per due instant under a stable membership view**: a node taking a
schedule over computes the next *future* occurrence, so a handoff may drop a tick but can never
replay one, and `ScheduleExecutor` fires only when this node both holds a write quorum and owns
the ring key — without the quorum gate a partitioned minority owns every schedule and re-runs it.
While the view is still converging after a join or leave, two nodes can each believe they own the
same key and both hold quorum, so an occurrence can still fire twice; a job that must not run
twice has to be idempotent. `nextRunAt` is
therefore never persisted — a durable `lastRunAt` would mean a DDL write per run and would churn
the schedule's replicated admin record. Missed runs while a node was down are skipped, not caught up, so a job that must
not miss an occurrence should be idempotent and driven off data rather than off the clock. A run
is deliberately **not** transactional (there is no run record to consume atomically); a job
wanting atomicity opens its own `db.transaction(…)`, which — unlike inside a trigger — is
permitted. `bckg_ops/ScheduleExecutor` owns the ticker plus its own queue and workers, skipping
anything this node does not own or that is still running. A run still waiting in that queue when its
schedule is deleted, disabled or changed is skipped: the dispatcher runs it only while it is still the
registered entry, so an acknowledged `DELETE_SCHEDULE` never fires a stale definition afterwards.
A run takes its definer's admission slices and is skipped (recorded as `SKIPPED`) when they are
full. `intervalMs` may not be below `scheduleMinIntervalMs` (`400-35`), and a stored or replicated
definition below it fires at that minimum; one user may define at most `scheduleMaxTotalPerUser`
schedules across every database (`400-33`), counted from the registry under one lock so two
databases' saves cannot both take the last slot.

### Pipeline scripts

The three `SCRIPT` surfaces inside an aggregation (a `FILTER` operator, a `MAP` `ADD_FIELD`
mid-operator, the `REDUCE` step) run on the `openCallable` seam, invoked once per document and
closed when the pipeline ends. **One interpreter per pipeline, not per document**: a fresh one
per document would dominate the cost, but the load-bearing reason is the budget — one interpreter
means one instruction budget, one deadline and one memory budget for the whole query, so a
runaway predicate aborts the query instead of getting a fresh budget on every row. The callable
comes from the module contract (`export default`, else a top-level `return`); an async or
generator function is rejected up front, since the call must not hop off the thread holding the
collection read locks.

There is **no `db`**, no `fetch` and no console: the aggregation already holds read locks on the
calling thread, and with no `db` a pipeline script cannot issue an `AGGREGATE` — there is no
recursion to bound. Memory is charged per document and released after the call, since without the
release a million-document scan would exhaust the budget on document count alone; a `REDUCE`'s
final accumulator is measured against `scriptMaxResultBytes`. The deadline is the *pipeline's*,
so a query carrying several scripts cannot outlive the timeout by opening more of them. The two
windows into a misbehaving pipeline script are `analyze` and the error itself, which names the
failing function and line.

### Run history

Unless `scriptRunHistoryEnabled` is off, a finished run is recorded in the reserved `script_runs`
collection **of the database it ran against**, so an operator asks what ran, what it cost and why
it failed with an ordinary `AGGREGATE` under ordinary permissions instead of grepping the log.
Those permissions are the database's: every user with `READ` on the database can read every row,
including the error text, stack and (with `scriptRunHistoryIncludeLogs`) console output of runs
by other users and of triggers and schedules that run with a definer's wider authority. Scripts
must therefore never log or throw secrets — a token read from another collection, a `fetch`
credential — because the history publishes them to every reader of the database.
The collection is created **lazily** on the first row (creating it with the database would change
`LIST_COLLECTIONS` for every database that never runs a script), is refused to client mutation
while staying open to reads/`CREATE_INDEX`/`REINDEX`, and is skipped by trigger dispatch and
before-hooks so an audit trigger cannot cascade on the record of itself. Writing is asynchronous
and best-effort through the ordinary request path, so ownership, quorum and replication are the
ones every other write gets; a failed write is counted and dropped, because the run it describes
has already committed. `scriptRunHistoryKinds` selects what is recorded (ad-hoc `RUN_SCRIPT` is
opt-in, since an exploratory client would write a row per keystroke; `BEFORE_HOOK` records
refusals only). A row carries the run's identity, outcome, attempt number and metrics; `skipped`
is the outcome no other surface reports — a trigger whose definer was deleted, a schedule whose
procedure is gone. An hourly owner-only sweep applies the retention.

Script-controlled text in a row (`errorName`, `errorMessage`, each `stack` and `logs` line) that is
shaped like a custom type — `#name(...)` — is stored with a leading backslash, so `#tag(fix)` reads
back as `\#tag(fix)`. The row is built in Java rather than parsed from the wire, and the reader
promotes every custom-shaped string, so without the escape an unregistered shape made the row
unreadable after an eviction or restart (skipped by every scan) and a registered one such as
`#geo(1,2)` came back as a custom value where the warm row held a string.

### Under clustering

`EnforcingDatabaseAccess.dispatch` consults `cluster/ClusterRouter` before running locally, so a
script sees the same routing a socket client does (`forward` returns `null` when clustering is
off, keeping the standalone path unchanged). A non-owned read or write is forwarded to the
collection's owner; a transactional write registers each foreign owner as a participant, so
`db.transaction` spans owners through the same 2PC the wire protocol uses.
`ops/resp/ResponseParser` rebuilds a typed response from the owner's JSON through each subclass's
public constructor, and `aggregate` forwards the caller's own pipeline JSON so a polymorphic
`CUSTOM` (geo/vector) operator survives the trip. The owner re-validates every forwarded request
and re-checks the script user's permission against its own copy of the users, so a grant made a
moment before the script ran may answer one `403-1` on an owner that has not applied it yet.
Script-run history writes to `script_runs` carry no user and skip that check (their names are still
validated).

**Where the run itself lands** is chosen by `cluster/ScriptPlacement` (gated by
`scriptRoutingEnabled`, default on): it samples two eligible members and takes the one with the
lower blend of script load and locality (`scriptLocalityWeight`) — power of two choices, since a
global minimum would herd on a gossip-stale view. A peer still catching up on admin metadata is
skipped, self is always a candidate, and any failure falls back to local execution. See
[clustering.md](clustering.md) → *Scripts*.

## Configuration

Every key is declared once, in `config/ConfigKey` — its name, type, shipped default and validation
rule. Parsing, defaults, validation and the "missing config" warning all read that one table, and
`ConfigKeyTest` fails the build if `default.cfg` and the registry ever disagree. `default.cfg` is the
documented reference; `lwnrdb.cfg` carries only a deployment's overrides. The request can never
influence any of them.

| Group | Keys |
|---|---|
| Enable | `scriptsEnabled`, `triggersEnabled`, `schedulesEnabled` |
| Sandbox | `scriptInstructionBudget`, `scriptTimeoutMs`, `scriptMaxDepth`, `scriptMaxMemoryBytes`, `scriptMaxSourceBytes`, `scriptMaxResultBytes`, `scriptMaxArrayLength` |
| Logs | `scriptMaxLogLines`, `scriptMaxLogLineChars` |
| Imports | `scriptProcedureImportEnabled` (on), `scriptTextImportEnabled` (off) |
| Cursors | `scriptCursorBatchSize`, `scriptCursorMaxBatchSize` |
| Egress | `scriptFetchEnabled` (on), `scriptFetchAllowlist` (`*`, public hosts only), `scriptFetchTimeoutMs`, `scriptFetchMaxResponseBytes`, `scriptFetchMaxConcurrent` (8) |
| Admission | `maxConcurrentScripts`, `scriptQueueWaitMs`, `maxConcurrentScriptsPerUser`, `maxConcurrentScriptsPerDatabase` |
| Caching | `procedureCacheSize` and `procedureCacheMaxBytes` (compiled procedures and parsed ad-hoc scripts, bounded by entries and by estimated heap; compiled outside the cache lock), `metadataCacheMaxBytes`, `metadataCacheMaxEntries` |
| Procedures | `maxProceduresPerDatabase` |
| Locale | `scriptTimeZone` (UTC), `scriptLocale` (en-US) |
| Triggers | `triggerThreads`, `triggerQueueSize`, `triggerQueueMaxBytes`, `triggerMaxPerCollection`, `triggerTimeoutMs`, `triggerMaxDepth`, `triggerRunLogEnabled`, `triggerRunRetentionMs`, `triggerMaxAttempts`, `triggerRetryBackoffMs`, `triggerRetryMaxBackoffMs`, `triggerDeadLetterRetentionMs` |
| Metadata caches | `metadataCacheMaxBytes` (procedure sources, schemas, schedule definitions), `metadataCacheMaxEntries` (trigger lists, negative lookups) |
| Before hooks | `beforeHookInstructionBudget`, `beforeHookTimeoutMs` |
| Schedules | `scheduleThreads`, `scheduleQueueSize`, `scheduleTickMs`, `scheduleRefreshMs`, `scheduleTimeoutMs`, `scheduleMaxPerDatabase`, `scheduleMaxTotalPerUser`, `scheduleMinIntervalMs` |
| Pipeline scripts | `aggregationScriptInstructionBudget`, `aggregationScriptTimeoutMs`, `aggregationScriptMaxSourceBytes` |
| History | `scriptRunHistoryEnabled`, `scriptRunHistoryKinds`, `scriptRunHistoryRetentionMs`, `scriptRunHistoryIncludeLogs`, `scriptRunHistoryMaxErrorChars` |
| Cluster | `scriptRoutingEnabled`, `scriptLocalityWeight` |

## Known gaps and divergences

Everything here is a **gap**: a conformant engine has it and SimpleJS either lacks it or gets it
wrong. The measured, exhaustive list is `config/test262-baseline.txt` (currently empty); these
are the ones that need an explanation rather than a test id, and none is reachable from ordinary
procedure/trigger/schedule code.

| Limitation | Why it stands |
|---|---|
| An array index at or past 2^31 assigned through `arr[i] = v` lands as a named property instead of updating `length` | `InterpreterUtils.arrayIndex` returns a Java `int`; widening it to `long` ripples into every caller. Indices up to 2^32−1 *are* representable and reachable through `Object.defineProperty`, `arr.length = N` and `new Array(N)`. |
| `Object.defineProperty` with a non-primitive `length` value throws `RangeError` | `defineOwnProperty` has no route to invoke user code for the coercion; threading `InterpreterOps` through would cascade into every override. |
| `super.m()` on a native super is an explicit `TypeError` | There are no native method tables to chain into — a loud failure rather than a silent wrong answer. |
| A function written inside a template substitution has no source text and no line/column | The lexer re-lexes each `${…}` into its own position-less token list, so there is no span to slice. Legal for `toString` (`HostHasSourceTextAvailable` is false); the frame degrades to a bare module name. |
| Property escapes do not match the UCD exhaustively | `built-ins/RegExp/property-escapes/generated/` scores 66.31% (311/469) — a genuine per-code-point gap between our resolution of a property name and the exhaustive set the corpus asserts. Property data is the build JDK's (Unicode 17.0 on the pinned JDK 27 floor), so a future JDK will move these escapes with it. |
| `scriptMaxMemoryBytes` does not bound O(1)-per-instruction allocation | By design — see *Sandbox and resource limits*. Sizing it against `scriptInstructionBudget` is the operator's job. |
| `localeCompare`/`toLocaleString` honour a locale but only part of `options` | The subset `java.text` can express without `Intl`: `sensitivity` maps onto `Collator`'s three strengths, and `usage`/`numeric`/`caseFirst`/`ignorePunctuation` are validated but not honoured. |
| A locale argument resolves to the closest locale the JDK ships, keeping only language, script and region | The JDK's locale-provider caches are static and never evicted, so a script naming a fresh tag per call (`'en-x-' + i`) used to grow the server heap for good and starve every tenant's scripts of the heap ceiling. `LocaleResolver` validates the tag (`Locale.Builder`; an ill-formed tag is a `RangeError`, as are `und` and a private-use-only tag), drops extensions and private-use subtags, and looks the language-script-region range up in `Locale.getAvailableLocales()` without ever building a `Locale` from the request. `'en-x-foo'` formats as `en`, `'en-US-u-nu-thai'` as plain `en-US`, and a language the JDK does not know falls back to `scriptLocale`. |
| `Temporal` fixed offsets beyond ±18:00 are unsupported | `java.time.ZoneOffset` hard-caps there and `ZoneRules` is `final` with no factory past it; supporting it would mean reimplementing every zone computation against a hand-rolled abstraction, for an offset magnitude no real IANA zone uses. Ten test262 cases are excluded by exact path. |

## Deliberately unimplemented ES2026 features

Each of these is a sandbox/security boundary or is unobservable in a single-threaded,
per-request interpreter, so omitting it is a design decision rather than a bug. This list is the
**specification of the conformance filter** — `config/test262-exclusions.txt` is its
machine-readable form, one line per decision carrying its reason, and an exclusion with no entry
here is a number being flattered.

- **`eval`, the `Function` constructor and its derived forms** (`GeneratorFunction`,
  `AsyncFunction`, `AsyncGeneratorFunction`), in every spelling including indirect `eval` and
  aliases — no runtime code generation from strings, which would defeat the instruction-budget
  sandbox and open an injection surface. The `script` module's `importText` runs a string as a
  *module*, which shares the caller's realm, loop, budget and deadline (so it can never buy extra
  compute) and is gated off by default for the injection half of the argument.
- **`ShadowRealm`** — code generation from a string, plus a second set of intrinsics per script
  that the per-`Interpreter` `Intrinsics` model does not provide.
- **`Intl`** — enormous; only ad-hoc `toLocaleString`/`localeCompare` defaults backed by
  `java.text` are provided. Non-ISO `Temporal` calendars fall under this too, since every such
  test262 case lives under `intl402/`.
- **`SharedArrayBuffer` + `Atomics`** — shared-memory multithreading is meaningless in a
  single-threaded per-connection VM.
- **`WeakRef` / `FinalizationRegistry`** — GC-observable behaviour cannot be exposed safely or
  deterministically. `WeakMap`/`WeakSet` exist but are strong; weakness is unobservable
  in-sandbox, and only the observable constraint (a primitive key throws) is enforced.
- **Arbitrary module resolution and npm** — the engine performs no filesystem or URL loading; a
  specifier resolves only if the host's `ModuleResolver` claims it. See *Engine status* for why
  npm is a non-goal rather than a pending phase.
- **`Symbol.species`** — `JsArray`/`JsTypedArray` are not subclassable, so species is
  unobservable and by-copy methods always allocate the default type.
- **The `with` statement** — forbidden in strict mode, and there is no sloppy mode.
- **Proper tail calls** — observable only through deep-recursion stack behaviour.
- **Leap seconds** — a `:60` seconds component is rejected, per spec.
- **Proposals outside the ES2026 snapshot** — immutable `ArrayBuffer`, `Iterator.zip`/`concat`,
  `JSON.rawJSON`, `await-dictionary`, decorators. A proposal that missed the snapshot is not a
  gap.

One exclusion is a measurement decision rather than a feature one:
`built-ins/RegExp/property-escapes/generated/` asserts, code point by code point, the contents of
one Unicode version — see the property-escape row of the gaps table.

## Measuring conformance

The official tc39/test262 corpus runs against `SimpleJs.run` through a harness in
`test_utils/test262/test262.py`, filtered to the surface a database script host actually exposes and
gated on a tracked baseline so the number can only ratchet upward.

```bash
python3 test_utils/test262/test262.py --fetch             # corpus fetched on demand (pinned + sha256, never committed)
python3 test_utils/test262/test262.py --self-test         # check the harness itself; no corpus needed
python3 test_utils/test262/test262.py --gate baseline     # what CI runs
python3 test_utils/test262/test262.py --update-baseline   # after fixing a gap
python3 test_utils/test262/test262.py --self-check        # assert the known divergences still fail
python3 test_utils/test262/test262.py --dump-failures     # re-run only the baselined ids, write the failure inventory
```

| File | Role |
|---|---|
| `config/test262.properties` | pinned corpus commit + tarball URL + sha256 |
| `config/test262-exclusions.txt` | what is deliberately not measured (a `keep:` line re-admits a subtree from a broader `dir:` exclusion, so a directory-wide omission cannot quietly swallow tests that fail for a reason we own) |
| `config/test262-baseline.txt` | known-failing ids, with the corpus SHA in the header |
| `config/test262-features.txt` | features already accounted for, so a corpus bump surfaces only the new ones |
| `test_utils/test262/shims/` | the `print`/`$DONE`/`$262` shims the corpus harness expects |
| `src/test/java/…/test262/Test262Worker.java` | the worker JVM the driver batches jobs onto |
| `test_log/test262-report.md` | the latest committed run: per-area rates, totals, exclusion/skip breakdown |

The rate is computed over `PASS + FAIL + HANG`; excluded and skipped tests are reported but never
counted in the denominator, so a deliberate omission cannot flatter the number. `noStrict` tests
are **skipped, not failed** — always-strict is a design decision. The gate fails on a new
failure, on a baselined test that now passes (fixes must be recorded), and on a baselined test the
filter has started excluding. Because the denominator moves as restriction-bound tests are
filtered out, a historical rate is comparable to today's only through the exclusion file, not by
its percentage.

## Testing conventions

Tests use JUnit 5, live under `src/test/java/org/techhouse/unit/simplejs/` mirroring the main
package structure, and follow the existing style (`assertInstanceOf`/`assertEquals`, one-line
intent comments):

- **Element/node tests** assert `getType()` for every concrete token and AST node, driving the
  `internalGetType` switches.
- **Lexer and parser tests** cover token-level behaviour, AST shape per construct, and negative
  cases; **program tests** run realistic snippets end to end through `source → lex → parse`.
- **Interpreter tests** assert behaviour through `Interpreter.run`/`SimpleJs.run`, one class per
  area (expressions/statements, objects, classes, generators, async, iteration, modules, timers,
  sandbox, ASI, strict mode, proxies, typed arrays, regex, Temporal).
- **Value, builtin and host tests** cover per-type and per-family units, `EJsonInterop` in both
  directions, and the host seam — `EnforcingDatabaseAccess` against a real
  `Cache`/`OperationProcessor` (an allowed save, a denied save, a schema-violating save).
- **Realm isolation** is asserted explicitly (`PrototypeProgramTest.test_realm_isolation`): one
  script's monkey-patch must not reach the next.

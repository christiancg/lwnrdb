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
   two must be sized together (see *Sandbox and resource limits*).
2. **Outbound `fetch` ships on, with its allowlist at `*`** — a capability behind a default-off
   flag is one nobody discovers, but the shipped posture hands a script the server's network
   position. `Main.warnIfScriptFetchEnabled` says so at every startup; operators are expected
   to narrow it.
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
| `values/` | The runtime value model: `JsValue` + `JsNumber`/`JsString`/`JsBoolean`/`JsBigInt`/`JsUndefined`/`JsNull`/`JsSymbol` (primitives) and `JsObject`/`JsArray`/`JsFunction`/`JsNativeFunction`/`JsClass`/`JsPromise`/`JsGenerator`/`JsAsyncGenerator`/`JsMap`/`JsSet`/`JsDate`/`JsRegExp`/`JsProxy`/`JsArguments`/`JsGlobalObject`, the binary trio `JsArrayBuffer`/`JsTypedArray`/`JsDataView`, the eight `JsTemporal*` types and the four EJson bridges `JsGeo`/`JsVector`/`JsDbDateTime`/`JsDbTime`. A dedicated model rather than EJson, so `undefined`/`null` and JS coercion stay faithful. `EJsonInterop` converts in both directions. |
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

Unicode property escapes resolve a name to a `CodePointSet` using `java.util.regex.Pattern` as a
one-time per-property **oracle** (compiled once, every code point tested, cached) — never as the
matching engine. Properties *of strings* (`\p{RGI_Emoji}` and siblings) have no JDK data at all
and are expanded from `src/main/resources/simplejs/emoji-sequences.txt`, which is ours and
pinned to Unicode 17.0. This is why the build JDK is pinned to exactly one version: the UCD comes
from the JDK, and two JDK versions would make `\p{…}` answer differently on two nodes of one
cluster. That version is **25, carrying Unicode 16.0**, because the shipped artifact is the GraalVM
native image and GraalVM has no JDK 26 release. So the two data sources sit one version apart —
properties *of strings* come from our own 17.0 file, single-code-point properties from the JDK's
16.0 data — and the eight `language/identifiers/*-unicode-17.0.0*` cases are excluded for that
reason (`config/test262-exclusions.txt`).

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

Per-run budgets bound **one** run; `ops/ScriptAdmission` bounds their sum. It caps
client-initiated runs at `maxConcurrentScripts` (admitting after a bounded `scriptQueueWaitMs`
wait, else `503-6`), then takes this caller's `maxConcurrentScriptsPerUser` and this database's
`maxConcurrentScriptsPerDatabase` slice — the inner two without waiting, since a tenant already
at its ceiling should be told at once rather than occupy a node-wide permit while it queues. The
returned `Permit` records which pools it took, so an inner refusal releases the outer one.
Triggers, hooks and schedules are exempt: they are already bounded by their own worker pools, and
a trigger refused for want of a permit would be a *dropped* trigger, not a retried one. The
node-wide ceiling on concurrent interpreters is therefore
`maxConcurrentScripts + triggerThreads + scheduleThreads`, and that sum times
`scriptMaxMemoryBytes` is the worst-case script heap to size `-Xmx` against — additive with
`maxMemory` and the metadata cache budgets.

`fetch` is bounded by `HostAllowlist` (an exact host, a `*.example.com` sub-domain wildcard, or a
bare `*`; an **empty** list denies everything even with the switch on), a response size cap and a
timeout, and runs off the interpreter thread as an `EventLoop` async job. It reaches
`RUN_SCRIPT`, `CALL_PROCEDURE`, triggers and schedules, and deliberately not pipeline scripts or
before-write hooks, which hold collection locks where a blocking egress call is a stall.

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
`scriptTextImportEnabled`). A default import of a built-in binds the built-in object itself,
while a default import of a real module binds its `default` export — `ModuleEvaluator` tags each
resolution accordingly. Only `export`ed bindings are importable, which is the one trap worth
knowing: a procedure written for `CALL_PROCEDURE` with a top-level `return` imports as
`{ default: undefined }`.

Everything else is the host's decision through `host/ModuleResolver`. The database host resolves
**`procedures/<name>`** (`ProcedureModuleResolver`, gated by `scriptProcedureImportEnabled`,
default on), so a library *is* a stored procedure — managed by the operations that already exist,
with nothing new persisted or replicated. The namespace is flat and scope-restricted (a `/` in
the name is refused; only the run's own database is searched), a missing or disabled procedure
answers `Cannot find module`, and the module id carries the procedure's version so a
`CompiledProcedureCache` entry can be reused across runs. Authority is the **importer's**:
importing widens what code is reachable, never what it may do. `SAVE_PROCEDURE` walks the
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

**Failure contract**: every method throws a catchable JS `Error` (built with the script's own
realm `Error.prototype`) whenever the underlying `OperationResponse` is not OK — a denial, a
schema violation, an entry-too-large, a cluster rejection, an internal error. Only genuine
absence stays a value: `findById` answers `null`, `aggregate` answers `[]`, and `delete` of an
absent id is a no-op.

**`db.cursor(database, collection, pipeline, options)`** walks a pipeline one batch at a time so
a script can read a collection larger than its memory budget. Each batch is an ordinary
`AGGREGATE` with `SKIP`/`LIMIT` appended to a *copy* of the caller's pipeline, so it is
authorized, schema-checked and cluster-routed exactly like a hand-written `db.aggregate` and adds
no cluster surface at all. It is **not** a snapshot (paging over a live collection can show a
document twice or not at all), **not** self-ordering (without a `SORT` step the paging is
meaningless, but injecting one would change the results of a pipeline ending in `GROUP_BY`), and
**not** stateful server-side (abandoning it holds nothing to release).

**`db.transaction(fn)`** runs `fn` in a transaction, committing on return and rolling back on
throw. The scoped-callback form is what makes it safe: a transaction holds each written
collection's write lock across calls and `ResourceLocking.releaseWrite` is thread-owned, so the
callback **may not suspend** — an async function, a generator and a returned promise are all
rejected with a `TypeError`, and `EnforcingDatabaseAccess` pins the session to the opening
thread. Because the rollback lives in Java, a sandbox abort still rolls back and releases the
locks. Three limitations: `listCollections`/`listDatabases` inside a transaction observe an empty
list rather than throwing; under clustering the 2PC round trips block the thread owning the
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
here degrades to its wire text rather than silently vanishing.

### What a run reports back

- **Result contract**: a top-level `return` if the module runs one, else `export default`, else
  an object of the named exports, else `undefined`. A promise in that position is **awaited** —
  a rejection becomes the script error, and one still pending after the drain fails the run
  (`400-20`) rather than quietly contributing JSON `null`. The conversion happens *inside* the
  interpreter's lifetime, so an accessor-valued property is read through its getter and the
  getter's work is charged to the run's budgets. The converted result is measured against
  `scriptMaxResultBytes` (`400-15`); a trigger passes `-1`, since its result is discarded.
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
  written per statement from positions the parser stamps. The trace is captured **when the error
  is constructed**, not when it is reported — the JS error object is built at the catch site,
  after every frame has unwound — through an `InheritableThreadLocal` so a coroutine's virtual
  thread sees it. Each coroutine swaps in its own stack segment per resumption, so a suspended
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
| `CALL_PROCEDURE` | **invoker** (caller) | yes | `script*` | returned | parse cached by `db\|name\|version` |
| After trigger | **definer** (installer) | yes | `script*`, `triggerTimeoutMs` | logged | async, exactly-once, retried |
| Before-write hook | recorded, not enforced | **no** | `beforeHook*` | discarded | synchronous, in the write lock, fail-closed |
| Schedule | **definer** | yes | `scheduleTimeoutMs` | logged | at-most-once per due instant |
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
owners have an implicit `MANAGE`. A legacy boolean reads as `RUN`/`NONE`.

**Errors** map to: `403-2` scripting disabled, `404-4` unknown database, `400-10` source too
large, `400-9` threw or would not parse, `400-11` budget/depth, `400-12` memory, `400-15` result
too large, `400-20` pending result, `408-1` timeout, `408-2` cancelled, `409-6` inside an open
client transaction. Console output and a stack ride along on every outcome.

### Triggers

A trigger binds a stored procedure to a collection's writes. **After** triggers run
asynchronously once the write has committed; **before** hooks run synchronously inside the write
lock and decide whether the write happens at all.

Both are installed with a **definer**, and an after trigger runs under **definer rights** rather
than the writer's. That is load-bearing: under invoker rights an audit trigger's effect would
depend on who wrote, and would fail for exactly the low-privilege users most worth auditing —
as a WARN in a log the writer never sees. The escalation is bounded by the fact that only an
admin, owner or `MANAGE` user can install one. Two consequences: a definer who no longer exists
**disables** the trigger (falling back to the writer would silently reinstate invoker rights, and
falling back to an admin would let *deleting* a user widen a trigger's authority), and a definer
whose permissions are later reduced silently narrows it.

**An after trigger runs exactly once, not at least once.** Before an event is queued,
`TriggerRunLog` persists a pending-run record in `admin/trigger_runs`; `TriggerDispatcher` then
runs the procedure inside a transaction whose final buffered op **consumes** that record, so the
run's effects and the evidence that would replay them commit together. `TriggerRunRecovery`
re-queues what is still pending at startup. This rests on single-node commits being crash-atomic
(`ops/TxCommitLog`), and at-least-once would be unusable for the ordinary case of a trigger that
increments a counter. Consequences: a run costs a transaction; an explicit `db.transaction(…)`
inside a trigger is rejected (the run is already transactional); and the guarantee covers
*database effects*, so a replayed run's console output can repeat.

A failed run is **retried** — the state machine lives on the pending-run record itself, which is
what keeps exactly-once intact (a record still present is still un-applied). A retryable failure
(a script error or a commit failure) increments `attempts` and re-queues after a doubling
backoff up to `triggerMaxAttempts`, after which the record is marked `DEAD` with its payload and
last error kept. Everything else is terminal and consumes the record: a missing definer or
procedure, exceeded depth, a queue-overflow drop, and a **cancellation** — the one place the
exactly-once guarantee is deliberately waived, because an operator cancelling a runaway trigger
wants it stopped. `LIST_TRIGGER_RUNS`/`RESOLVE_TRIGGER_RUN` are the admin-only operator surface,
fanned out cluster-wide because `admin/trigger_runs` is not replicated.

Triggers are **queued** from `OperationProcessor`'s write handlers and `TransactionOperationHelper.commit`
— never from the write helpers, since a replicated apply reaches those directly and would fire
once per replica. Enqueueing is one map lookup and a queue offer, so it is safe inside the write
lock. `bckg_ops/TriggerExecutor` runs them on **its own** bounded queue and workers, deliberately
not the background index queue: a trigger runs arbitrary user code, and sharing that queue would
let one slow trigger stall field-index maintenance for every collection. On overflow the oldest
event is dropped and counted. Cascades carry `triggerDepth + 1` on the request itself — a field,
not a `ThreadLocal`, so the bound survives a cluster forward — zeroed for client requests;
`allowCascade` defaults to false.

**Before-write hooks** are the veto. Returning nothing or `true` accepts the write, a plain
object replaces the document, anything else or a `throw` refuses it (`400-21`); an abort keeps
its own code, so an operator can tell a hook that said no from one that never finished. Every
failure is **fail-closed**. They run on the `openCallable` seam rather than `SimpleJs.run`, which
is the entry point whose call happens on the caller's thread (the write lock is thread-owned) and
which gives **one interpreter per request** — a `BULK_SAVE` of N documents evaluates the body
once and shares one budget across all N invocations. There is **no `db`**: a re-entrant call from
under a held write lock would take a lock this thread owns, or make a network round trip while a
writer waits; the module resolver is kept so a hook can import shared code, since that takes no
locks. A replacement may not change `_id` (that would relocate the document, turning an update
into an insert elsewhere) and is re-validated against the collection's JSON Schema, which runs at
the *edge*, before the hook. Several hooks chain in ascending name order and the first refusal
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

Delivery is **at-most-once per due instant**: a node taking a schedule over computes the next
*future* occurrence, so a handoff may drop a tick but can never replay one. `nextRunAt` is
therefore never persisted — a durable `lastRunAt` would mean a DDL write per run and would churn
the admin epoch. Missed runs while a node was down are skipped, not caught up, so a job that must
not miss an occurrence should be idempotent and driven off data rather than off the clock. A run
is deliberately **not** transactional (there is no run record to consume atomically); a job
wanting atomicity opens its own `db.transaction(…)`, which — unlike inside a trigger — is
permitted. `bckg_ops/ScheduleExecutor` owns the ticker plus its own queue and workers, skipping
anything this node does not own or that is still running.

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

### Under clustering

`EnforcingDatabaseAccess.dispatch` consults `cluster/ClusterRouter` before running locally, so a
script sees the same routing a socket client does (`forward` returns `null` when clustering is
off, keeping the standalone path unchanged). A non-owned read or write is forwarded to the
collection's owner; a transactional write registers each foreign owner as a participant, so
`db.transaction` spans owners through the same 2PC the wire protocol uses.
`ops/resp/ResponseParser` rebuilds a typed response from the owner's JSON through each subclass's
public constructor, and `aggregate` forwards the caller's own pipeline JSON so a polymorphic
`CUSTOM` (geo/vector) operator survives the trip.

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
| Sandbox | `scriptInstructionBudget`, `scriptTimeoutMs`, `scriptMaxDepth`, `scriptMaxMemoryBytes`, `scriptMaxSourceBytes`, `scriptMaxResultBytes` |
| Logs | `scriptMaxLogLines`, `scriptMaxLogLineChars` |
| Imports | `scriptProcedureImportEnabled` (on), `scriptTextImportEnabled` (off) |
| Cursors | `scriptCursorBatchSize`, `scriptCursorMaxBatchSize` |
| Egress | `scriptFetchEnabled` (on), `scriptFetchAllowlist` (`*`), `scriptFetchTimeoutMs`, `scriptFetchMaxResponseBytes` |
| Admission | `maxConcurrentScripts`, `scriptQueueWaitMs`, `maxConcurrentScriptsPerUser`, `maxConcurrentScriptsPerDatabase` |
| Caching | `procedureCacheSize` (compiled procedures and parsed ad-hoc scripts), `metadataCacheMaxBytes`, `metadataCacheMaxEntries` |
| Locale | `scriptTimeZone` (UTC), `scriptLocale` (en-US) |
| Triggers | `triggerThreads`, `triggerQueueSize`, `triggerTimeoutMs`, `triggerMaxDepth`, `triggerRunLogEnabled`, `triggerRunRetentionMs`, `triggerMaxAttempts`, `triggerRetryBackoffMs`, `triggerRetryMaxBackoffMs`, `triggerDeadLetterRetentionMs` |
| Metadata caches | `metadataCacheMaxBytes` (procedure sources, schemas, schedule definitions), `metadataCacheMaxEntries` (trigger lists, negative lookups) |
| Before hooks | `beforeHookInstructionBudget`, `beforeHookTimeoutMs` |
| Schedules | `scheduleThreads`, `scheduleQueueSize`, `scheduleTickMs`, `scheduleRefreshMs`, `scheduleTimeoutMs`, `scheduleMaxPerDatabase` |
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
| Property escapes do not match the UCD exhaustively | `built-ins/RegExp/property-escapes/generated/` scores 66.31% (311/469) — a genuine per-code-point gap between our resolution of a property name and the exhaustive set the corpus asserts. Property data is the build JDK's (Unicode 16.0 on the pinned JDK 25 floor), so a future JDK will move these escapes with it. |
| `scriptMaxMemoryBytes` does not bound O(1)-per-instruction allocation | By design — see *Sandbox and resource limits*. Sizing it against `scriptInstructionBudget` is the operator's job. |
| `localeCompare`/`toLocaleString` honour a locale but only part of `options` | The subset `java.text` can express without `Intl`: `sensitivity` maps onto `Collator`'s three strengths, and `usage`/`numeric`/`caseFirst`/`ignorePunctuation` are validated but not honoured. |
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

Two exclusions are measurement decisions rather than feature ones:
`built-ins/RegExp/property-escapes/generated/` asserts, code point by code point, the contents of
one Unicode version — see the property-escape row of the gaps table — and
`language/identifiers/*-unicode-17.0.0*` asserts identifier code points added in a Unicode version
newer than the build JDK's, as described under *Regular expressions*.

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

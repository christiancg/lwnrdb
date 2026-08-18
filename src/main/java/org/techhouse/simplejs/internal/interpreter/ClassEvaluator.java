package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.staticKeyName;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.internal.Completion;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.ClassBody;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ClassExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.FieldDefinition;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.StaticBlock;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// Class semantics: building a JsClass from its declaration/expression, instance construction and
// the constructor chain, field initialisation, `super` calls/reads, `instanceof` and private-brand
// checks. Everything that recurses into general evaluation (evaluating heritage, keys, field
// initialisers, arguments, invoking methods) routes through the Interpreter seam.
public final class ClassEvaluator {
    private final Interpreter interp;

    // Our storage model has no home for a Proxy's own private-field table (unlike the spec, where a
    // Proxy exotic object genuinely carries its own [[PrivateElements]] slot): a base constructor
    // that returns `new Proxy(this, ...)` makes InitializeInstanceElements install fields directly on
    // the unwrapped target instead (see unwrapForFieldInit). That target is reachable through *this
    // specific proxy identity* only - an unrelated `new Proxy(target, {})` created later, wrapping the
    // very same target, must NOT also see it (private fields are keyed by exact object identity, not
    // by "wraps the same target"). Every proxy layer peeled during field initialisation is recorded
    // here against the storage it resolved to, and a private access consults this map instead of
    // re-deriving the answer by unwrapping again - which would treat any proxy over that target as
    // equally entitled.
    private final Map<JsValue, JsObject> privateStorageBridges = new IdentityHashMap<>();

    public ClassEvaluator(Interpreter interp) {
        this.interp = interp;
    }

    // The single choke point Interpreter.getPrivateMember/setPrivateMember use to resolve a private
    // access target to its backing storage: a plain object is its own storage, a bridged proxy layer
    // resolves to what it was recorded against, and anything else (an unrelated/unbridged proxy, a
    // primitive) resolves to null.
    public JsObject resolvePrivateStorage(JsValue target) {
        if (target instanceof JsObject object) {
            return object;
        }
        return privateStorageBridges.get(target);
    }

    public Completion evalClassDeclaration(ClassDeclaration declaration, Environment env) {
        final var cls = buildClass(declaration.getId(), declaration.getSuperClass(), declaration.getBody(), env);
        final var name = declaration.getId().getName();
        env.declareLexical(name, "let");
        env.initialize(name, cls);
        return Completion.empty();
    }

    public JsValue evalClassExpression(ClassExpression expression, Environment env) {
        return buildClass(expression.getId(), expression.getSuperClass(), expression.getBody(), env);
    }

    private JsClass buildClass(Identifier id, Expression superClassExpr, ClassBody body, Environment env) {
        // ClassDefinitionEvaluation creates the class's own scope - with the class name bound but
        // uninitialized (TDZ) - and switches into it *before* evaluating ClassHeritage, so `class x
        // extends x {}` throws a ReferenceError from the TDZ read rather than resolving `x` in the
        // enclosing scope.
        final var classScope = env.child();
        final var name = id == null ? null : id.getName();
        if (name != null) {
            classScope.declareLexical(name, "const");
        }
        JsClass superClass = null;
        JsValue superConstructor = null;
        var nullHeritage = false;
        if (superClassExpr != null) {
            final var resolved = interp.eval(superClassExpr, classScope);
            if (resolved instanceof JsClass sc) {
                superClass = sc;
            } else if (resolved instanceof JsNull) {
                nullHeritage = true;
            } else if (InterpreterUtils.isConstructor(resolved)) {
                superConstructor = resolved;
            } else {
                throw new TypeErrorException(
                        "Class extends value " + JsCoercion.toStr(resolved) + " is not a constructor or null");
            }
        }
        final var methodScope = classScope.child();
        final var cls = new JsClass(name, superClass, methodScope);
        if (nullHeritage) {
            cls.markNullHeritage();
            // The prototype object has no parent, but the constructor function itself still chains
            // to %Function.prototype% like any other heritage-less constructor.
            cls.setProto(interp.intrinsics().functionProto());
        } else if (superConstructor != null) {
            // ClassDefinitionEvaluation reads Get(superclass, "prototype"), so an accessor-valued or
            // assigned `prototype` on the heritage is observed here; anything that is neither an
            // object nor null is a TypeError before the body is evaluated.
            final var parentPrototype = interp.getMember(superConstructor, "prototype");
            if (!isObjectLike(parentPrototype) && !(parentPrototype instanceof JsNull)) {
                throw new TypeErrorException("Class extends value does not have valid prototype property "
                        + JsCoercion.toStr(parentPrototype));
            }
            cls.setSuperConstructor(superConstructor, parentPrototype instanceof JsNull ? null : parentPrototype);
        } else if (superClass == null) {
            cls.setProto(interp.intrinsics().functionProto());
            cls.getPrototype().setProto(interp.intrinsics().objectProto());
        }
        methodScope.defineHomeClass(cls);
        declarePrivateNames(cls, body);
        classScope.definePrivateEnvironment(cls);
        if (name != null) {
            classScope.initialize(name, cls);
        }
        final var staticInit = new ArrayList<StaticEntry>();
        for (final var member : body.getMembers()) {
            switch (member) {
                case MethodDefinition method -> installMethod(cls, method, classScope);
                case FieldDefinition field -> {
                    // ClassFieldDefinitionEvaluation runs ToPropertyKey once, at class definition
                    // time and in source order, so an abrupt completion escapes before the member
                    // is installed and a later instantiation never re-evaluates the key.
                    final var key = fieldKey(field, classScope);
                    if (field.isStatic()) {
                        staticInit.add(new StaticEntry(field, null, key));
                    } else {
                        cls.addInstanceField(field, key);
                    }
                }
                case StaticBlock block -> staticInit.add(new StaticEntry(null, block, null));
                default -> throw new UnsupportedNodeException(member.getType().name());
            }
        }
        runStaticInit(cls, staticInit);
        return cls;
    }

    private record StaticEntry(FieldDefinition field, StaticBlock block, JsValue key) {
    }

    private JsValue fieldKey(FieldDefinition field, Environment classScope) {
        if (!field.isComputed()) {
            return null;
        }
        final var key = JsCoercion.toPropertyKey(interp.eval(field.getKey(), classScope), interp.ops());
        if (field.isStatic() && isNamed(key)) {
            throw new TypeErrorException("Classes may not have a static field named 'prototype'");
        }
        // Unlike the literal-name early error the parser enforces (Static Semantics: PropName),
        // PropName of a ComputedPropertyName is always *empty* - so a computed field name that
        // merely evaluates to "constructor" at run time is not the same restriction and must not be
        // rejected: DefineField's CreateDataPropertyOrThrow happily installs an own "constructor"
        // data property on a plain instance (there is nothing there to collide with).
        return key;
    }

    private static boolean isNamed(JsValue key) {
        return key instanceof JsString string && "prototype".equals(string.getValue());
    }

    // PrivateBoundIdentifiers of a ClassBody: the private-named members' keys, all created before the
    // first member (or computed key) is evaluated.
    private static void declarePrivateNames(JsClass cls, ClassBody body) {
        for (final var member : body.getMembers()) {
            final var key = switch (member) {
                case MethodDefinition method -> method.getKey();
                case FieldDefinition field -> field.getKey();
                default -> null;
            };
            if (key instanceof PrivateIdentifier priv) {
                cls.declarePrivateName(priv.getName());
            }
        }
    }

    private void installMethod(JsClass cls, MethodDefinition method, Environment classScope) {
        final var value = method.getValue();
        final var fn = interp.makeFunction(null, value.getParams(), value.getBody(), false, false, value.isAsync(),
                value.isGenerator(), cls.getMethodScope());
        final var kind = method.getKind();
        if ("constructor".equals(kind)) {
            if (cls.isDerived()) {
                fn.markDerivedConstructor();
            }
            cls.setConstructor(fn);
            return;
        }
        if (!fn.isGenerator()) {
            fn.markMethod();
        }
        if (method.getKey() instanceof PrivateIdentifier priv) {
            final var privateName = cls.declarePrivateName(priv.getName());
            fn.setInferredName(accessorName(kind, "#" + priv.getName()));
            if (method.isStatic()) {
                cls.addPrivateStaticMethod(privateName, kind, fn);
            } else {
                cls.addPrivateInstanceMethod(privateName, kind, fn);
            }
            return;
        }
        if (method.isComputed()) {
            final var keyValue = JsCoercion.toPropertyKey(interp.eval(method.getKey(), classScope), interp.ops());
            if (method.isStatic() && isNamed(keyValue)) {
                throw new TypeErrorException("Classes may not have a static method named 'prototype'");
            }
            if (keyValue instanceof JsSymbol symbol) {
                fn.setInferredName(accessorName(kind, symbolMethodName(symbol)));
                if (method.isStatic()) {
                    cls.addStaticSymbolMethod(symbol, kind, fn);
                    publishSymbolMember(cls.getStaticOwner(), symbol, kind, fn);
                } else {
                    cls.addInstanceSymbolMethod(symbol, kind, fn);
                    publishSymbolMember(cls.getPrototype(), symbol, kind, fn);
                }
                return;
            }
            installStringMethod(cls, method, kind, fn, JsCoercion.toStr(keyValue));
            return;
        }
        installStringMethod(cls, method, kind, fn, staticKeyName(method.getKey()));
    }

    // SetFunctionName's accessor prefix, plus the "[description]" form a symbol-keyed member takes.
    static String accessorName(String kind, String key) {
        return "get".equals(kind) || "set".equals(kind) ? kind + " " + key : key;
    }

    // The symbol tables stay authoritative for dispatch, but the member is mirrored onto the real
    // prototype/static object so it is reflectively visible (getOwnPropertySymbols,
    // getOwnPropertyDescriptor) with the spec's non-enumerable method attributes.
    private static void publishSymbolMember(JsObject owner, JsSymbol symbol, String kind, JsFunction fn) {
        if ("get".equals(kind)) {
            owner.defineSymbolAccessor(symbol, fn, null);
        } else if ("set".equals(kind)) {
            owner.defineSymbolAccessor(symbol, null, fn);
        } else {
            owner.setSymbol(symbol, fn);
        }
        owner.setSymbolFlags(symbol, new JsObject.PropertyFlags(true, false, true));
    }

    private static void nameField(FieldDefinition field, JsValue value, String key) {
        InterpreterUtils.applyInferredName(field.getValue(), value, key);
    }

    static String symbolMethodName(JsSymbol symbol) {
        final var description = symbol.getDescription();
        return description == null ? "" : "[" + description + "]";
    }

    private void installStringMethod(JsClass cls, MethodDefinition method, String kind, JsFunction fn, String key) {
        fn.setInferredName(accessorName(kind, key));
        if (method.isStatic()) {
            cls.addStaticMethod(key, kind, fn);
        } else {
            cls.addInstanceMethod(key, kind, fn);
        }
    }

    private void runStaticInit(JsClass cls, List<StaticEntry> staticInit) {
        final var staticScope = cls.getMethodScope().child();
        staticScope.defineThis(cls);
        for (final var entry : staticInit) {
            if (entry.field() != null) {
                runStaticField(cls, entry, staticScope);
            } else {
                // ClassStaticBlockDefinitionEvaluation: OrdinaryFunctionCreate makes each static block
                // its own function, so a `var` inside it hoists to that block's own scope rather than
                // leaking into the class's shared static scope (other blocks/fields) or, worse, past
                // it into whatever ordinary function/script scope encloses the class declaration.
                final var blockEnv = staticScope.functionChild();
                interp.hoist(entry.block().getBody(), blockEnv);
                for (final var statement : entry.block().getBody()) {
                    if (!interp.evalStatement(statement, blockEnv).isNormal()) {
                        break;
                    }
                }
            }
        }
    }

    private void runStaticField(JsClass cls, StaticEntry entry, Environment staticScope) {
        final var field = entry.field();
        final var value = field.getValue() == null
                ? JsUndefined.getInstance()
                : interp.eval(field.getValue(), staticScope);
        if (field.getKey() instanceof PrivateIdentifier priv) {
            nameField(field, value, "#" + priv.getName());
            if (!cls.addPrivateStaticField(cls.declarePrivateName(priv.getName()), value)) {
                throw new TypeErrorException("Cannot add private field #" + priv.getName() + " to this class object");
            }
            return;
        }
        if (entry.key() instanceof JsSymbol symbol) {
            nameField(field, value, symbolMethodName(symbol));
            cls.setStaticSymbolProp(symbol, value);
            return;
        }
        final var key = entry.key() == null ? staticKeyName(field.getKey()) : ((JsString) entry.key()).getValue();
        nameField(field, value, key);
        cls.defineStaticField(key, value);
    }

    public JsValue construct(JsClass cls, List<JsValue> args, JsValue newTarget) {
        final var instance = new JsObject();
        instance.setKlass(cls);
        // OrdinaryCreateFromConstructor: an ordinary Get(newTarget, "prototype"), so an
        // accessor-valued or throwing `prototype` behaves like any other property read.
        final var declared = interp.getMemberByKey(newTarget, new JsString("prototype"));
        instance.setProto(isObjectLike(declared) ? declared : cls.getPrototype());
        return callConstructorChain(cls, instance, args, newTarget);
    }

    // The chain returns the effective `this`: a constructor that returns an object overrides the
    // instance, and a derived class then initialises its own fields on that object instead.
    private JsValue callConstructorChain(JsClass cls, JsObject instance, List<JsValue> args, JsValue newTarget) {
        final var constructor = cls.getConstructor();
        final var superCtor = cls.getSuperClass() == null ? cls.getSuperConstructor() : null;
        // A derived class with its own constructor reaches its heritage through that constructor's
        // super() call, so running the super here too would construct the base twice.
        if (constructor != null && cls.isDerived()) {
            return overrideOrInstance(interp.callFunction(constructor, instance, args, newTarget), instance);
        }
        var self = (JsValue) instance;
        if (superCtor != null) {
            self = applySuperConstructor(superCtor, instance, args, newTarget);
        } else if (cls.hasNullHeritage()) {
            throw new TypeErrorException("Super constructor null of " + cls.getName() + " is not a constructor");
        } else if (cls.getSuperClass() != null) {
            self = callConstructorChain(cls.getSuperClass(), instance, args, newTarget);
        }
        final var fieldTarget = unwrapForFieldInit(self);
        if (fieldTarget != null) {
            initFields(cls, self, fieldTarget);
        }
        if (constructor != null) {
            return overrideOrInstance(interp.callFunction(constructor, self, args, newTarget), self);
        }
        return self;
    }

    private static JsValue overrideOrInstance(JsValue returned, JsValue instance) {
        return isObjectLike(returned) ? returned : instance;
    }

    // InitializeInstanceElements installs private/public fields on whatever `this` resolved to,
    // which may be a Proxy when a base constructor in the chain returned one (PrivateFieldAdd and
    // CreateDataPropertyOrThrow both act on the object directly, bypassing any [[DefineProperty]]
    // trap) - our private-member storage lives on the concrete JsObject, so the field lands on the
    // proxy's (possibly nested) target rather than being silently skipped. Every peeled proxy layer
    // is bridged to that target in privateStorageBridges, so a later private access reaching this
    // exact proxy identity (not just anything wrapping the same target) can resolve it.
    private JsObject unwrapForFieldInit(JsValue self) {
        final var layers = new ArrayList<JsValue>();
        var current = self;
        while (current instanceof JsProxy proxy) {
            layers.add(current);
            current = proxy.getTarget();
        }
        if (!(current instanceof JsObject object)) {
            return null;
        }
        for (final var layer : layers) {
            privateStorageBridges.put(layer, object);
        }
        return object;
    }

    // A non-class heritage: a user function is invoked as an ordinary [[Construct]] against the
    // instance under construction (its body writes through `this`), and - per OrdinaryCallEvaluateBody
    // step 6 - a returned object overrides that instance as the effective `this` of the whole chain. A
    // builtin instead goes through the copy path that keeps its internal state (there is no `this`
    // parameter it can write through), and anything else constructible is constructed and its own
    // state adopted.
    private JsValue applySuperConstructor(JsValue superCtor, JsObject instance, List<JsValue> args, JsValue newTarget) {
        return switch (superCtor) {
            case JsNativeFunction nativeSuper -> {
                applyNativeSuper(nativeSuper, instance, args);
                yield instance;
            }
            case JsFunction function ->
                overrideOrInstance(interp.callFunction(function, instance, args, newTarget), instance);
            default -> {
                adoptConstructed(interp.construct(superCtor, args, newTarget), instance);
                yield instance;
            }
        };
    }

    // A native super has no method tables to chain into, so its result's own state is copied onto the
    // instance and the instance is linked to the native prototype for method lookup and instanceof.
    // The instance under construction is passed as thisArg (a plain `new NativeCtor()` passes
    // JsUndefined instead), giving an abstract native constructor like Iterator's a signal to
    // distinguish a super() call from a subclass from direct construction. Deliberately NOT threading
    // the active new.target through to `invoke`: several natives (TypedArray/DataView/ArrayBuffer)
    // use JsNativeFunction.currentNewTarget() to decide whether to wrap their result for
    // Reflect.construct-style foreign-prototype subclassing, a decision that does not apply here -
    // the instance's own prototype is already linked from new.target one level up in
    // JsClass.construct, and `adoptConstructed` below expects the raw internal-state value, not a
    // wrapper. A native that must reject *any* construction, direct or via super() (Symbol), tells
    // the two apart via `thisArg` instead (present here, undefined on a bare call).
    private void applyNativeSuper(JsNativeFunction nativeSuper, JsObject instance, List<JsValue> args) {
        adoptConstructed(nativeSuper.invoke(instance, args), instance);
    }

    private static void adoptConstructed(JsValue produced, JsObject instance) {
        if (produced instanceof JsObject object) {
            for (final var key : object.keys()) {
                instance.defineValue(key, object.get(key));
                instance.setFlags(key, object.getFlags(key));
            }
            if (object.isErrorData()) {
                instance.markErrorData();
            }
        } else {
            // A builtin with internal state (Map/Set/Date/Array/…) cannot be copied onto a plain
            // instance, so the produced value is kept as the instance's wrapped primitive and the
            // intrinsic prototype methods unwrap it from their receiver.
            instance.setPrimitive(produced);
        }
    }

    // `self` is the receiver InitializeInstanceElements actually operates on (may be a Proxy, when a
    // super constructor in the chain returned one) - a public/symbol field goes through it via a
    // trap-aware CreateDataPropertyOrThrow, matching DefineField. Private storage cannot go through a
    // Proxy at all (PrivateFieldAdd has no trap and our storage lives on the concrete JsObject), so
    // that part uses `storage` (the same value unwrapped for field init, see `unwrapForFieldInit`).
    private void initFields(JsClass cls, JsValue self, JsObject storage) {
        // PrivateMethodOrAccessorAdd's brand entry is installed on the same instance a private field
        // would be (PrivateFieldAdd), so a class that declares any private method/accessor rejects a
        // non-extensible receiver exactly as a private field would - only relevant when this class
        // actually has something to brand (a class with none never observes non-extensibility here).
        if (cls.hasPrivateInstanceBrand() && !storage.isExtensible()) {
            throw new TypeErrorException(
                    "Cannot add private members to a non-extensible object (" + cls.getName() + ")");
        }
        if (!storage.addPrivateBrand(cls) && cls.hasPrivateInstanceBrand()) {
            throw new TypeErrorException(
                    "Cannot initialize the private members of " + cls.getName() + " twice on the same object");
        }
        for (final var entry : cls.getInstanceFields()) {
            final var field = entry.definition();
            final var fieldScope = cls.getMethodScope().child();
            fieldScope.defineThis(self);
            final var value = field.getValue() == null
                    ? JsUndefined.getInstance()
                    : interp.eval(field.getValue(), fieldScope);
            if (field.getKey() instanceof PrivateIdentifier priv) {
                nameField(field, value, "#" + priv.getName());
                if (!storage.addPrivate(cls.declarePrivateName(priv.getName()), value)) {
                    throw new TypeErrorException("Cannot add private field #" + priv.getName() + " to this object");
                }
            } else if (entry.key() instanceof JsSymbol symbol) {
                nameField(field, value, symbolMethodName(symbol));
                defineFieldProperty(self, symbol, value);
            } else {
                final var key = entry.key() == null
                        ? staticKeyName(field.getKey())
                        : ((JsString) entry.key()).getValue();
                nameField(field, value, key);
                defineFieldProperty(self, new JsString(key), value);
            }
        }
    }

    // DefineField's non-private branch: CreateDataPropertyOrThrow(receiver, fieldName, initValue) -
    // an ordinary [[DefineOwnProperty]], so a Proxy receiver's `defineProperty` trap fires and a
    // non-extensible (frozen) receiver rejects a new key instead of silently dropping the field.
    private void defineFieldProperty(JsValue self, JsValue key, JsValue value) {
        final var descriptor = new JsObject();
        descriptor.setProto(interp.intrinsics().objectProto());
        descriptor.set("value", value);
        descriptor.set("writable", JsBoolean.TRUE);
        descriptor.set("enumerable", JsBoolean.TRUE);
        descriptor.set("configurable", JsBoolean.TRUE);
        if (!interp.ops().defineProperty(self, key, descriptor)) {
            throw new TypeErrorException(
                    "Cannot define field '" + JsCoercion.toStr(key, interp.ops()) + "' on this object");
        }
    }

    public JsValue evalSuperCall(CallExpression call, Environment env) {
        final var home = superHomeClass(env);
        final var thisValue = env.resolveThisBeforeSuper();
        if (!(thisValue instanceof JsObject instance)) {
            throw new TypeErrorException("'super' call outside of a constructor");
        }
        // ArgumentListEvaluation runs before the IsConstructor check (SuperCall step 4-5), so a side
        // effect in an argument expression is observed even when the resolved super constructor turns
        // out not to be callable at all (e.g. its slot was reassigned to a non-constructor value, or a
        // `extends null` class's constructorParent is %Function.prototype%).
        final var args = interp.evalArguments(call.getArguments(), env);
        if (!InterpreterUtils.isConstructor(home.getProto())) {
            throw new TypeErrorException("Super constructor " + JsCoercion.toStr(home.getProto()) + " of "
                    + home.getName() + " is not a constructor");
        }
        final JsValue self = home.getSuperClass() == null
                ? applySuperConstructor(home.getSuperConstructor(), instance, args, env.resolveNewTarget())
                : callConstructorChain(home.getSuperClass(), instance, args, env.resolveNewTarget());
        // BindThisValue's own "already initialized" guard runs after Construct (SuperCall step 8, well
        // after ArgumentListEvaluation and Construct at steps 4/6) - a nested/repeated super() call's
        // side effects (argument evaluation, the base constructor running again) are observable before
        // this throws, so the check cannot move any earlier than here.
        if (env.isThisInitialized()) {
            throw new ReferenceErrorException("Super constructor may only be called once");
        }
        // A base constructor that returns an object becomes this derived constructor's `this`, so the
        // derived class's fields and brand land on that object.
        if (self != instance) {
            env.replaceThis(self);
        }
        env.markThisInitialized();
        final var fieldTarget = unwrapForFieldInit(self);
        if (fieldTarget != null) {
            initFields(home, self, fieldTarget);
        }
        // SuperCall evaluates to BindThisValue's result: the same value now bound as `this`.
        return self;
    }

    public JsValue evalSuperMemberCall(MemberExpression member, CallExpression call, Environment env) {
        final var thisArg = env.resolveThis();
        // GetSuperBase() is captured before the property key is resolved/coerced (MakeSuperPropertyReference
        // runs before GetValue's deferred ToPropertyKey), so a side effect during key evaluation must not
        // change which object the read is dispatched against. A static method's home is the class
        // itself, so its GetSuperBase() is the class's own (dynamic) [[Prototype]] - a class heritage,
        // a non-class constructor, or %Function.prototype% by default - not the instance chain.
        final var start = thisArg instanceof JsClass cls ? cls.getProto() : superProtoStart(env);
        final var rawKey = interp.memberKeyValue(member, env);
        final var args = interp.evalArguments(call.getArguments(), env);
        final var value = superRead(start, rawKey, thisArg);
        if (isCallable(value)) {
            return interp.callValue(value, thisArg, args);
        }
        throw new TypeErrorException("(intermediate value).super." + keyDisplay(rawKey) + " is not a function");
    }

    // A computed super key may evaluate to a Symbol (e.g. `super[Symbol.replace](...)`), which
    // ToPropertyKey/ToString would reject outright - route a Symbol key through the object's symbol
    // table instead of stringifying it, mirroring the string-keyed path below.
    private JsValue superRead(JsValue start, JsValue rawKey, JsValue receiver) {
        if (rawKey instanceof JsSymbol symbol) {
            return superProtoSymbolRead(start, symbol, receiver);
        }
        return superProtoRead(start, JsCoercion.toStr(rawKey, interp.ops()), receiver);
    }

    private String keyDisplay(JsValue rawKey) {
        return rawKey instanceof JsSymbol symbol
                ? "Symbol(" + (symbol.getDescription() == null ? "" : symbol.getDescription()) + ")"
                : JsCoercion.toStr(rawKey, interp.ops());
    }

    // GetSuperBase's proto-chain walk for a Symbol key: each link's own symbol table is consulted
    // (accessor first, then a plain value) before advancing to its [[Prototype]] - the same shape as
    // MemberEvaluator's private chainSymbolMember, reimplemented here against the object's public
    // symbol accessors since that chain walker isn't reachable from this class. A non-JsObject link
    // (a native super's own prototype, reached at most once) falls back to its realm-intrinsic
    // prototype, same as the string-keyed walk.
    private JsValue superProtoSymbolRead(JsValue start, JsSymbol symbol, JsValue receiver) {
        var link = start;
        var synthesised = false;
        while (link != null) {
            if (link instanceof JsObject object) {
                if (object.hasSymbolAccessor(symbol)) {
                    final var getter = object.getSymbolAccessorGetter(symbol);
                    return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, receiver, List.of());
                }
                if (object.hasSymbol(symbol)) {
                    return object.getSymbol(symbol);
                }
            }
            final var next = link.getProto();
            if (next == null && !synthesised && !(link instanceof JsObject)) {
                link = interp.intrinsics().protoFor(link);
                synthesised = true;
                continue;
            }
            link = next;
        }
        return JsUndefined.getInstance();
    }

    // PutValue on a super reference is Set(GetSuperBase(), key, value, thisValue): the receiver is
    // `this`, so an absent setter on the super chain writes an own property on the instance. The base
    // for the [[Set]] is GetSuperBase() itself (captured before the key is resolved, same ordering
    // rationale as the read/call paths above) - not `thisArg`, which would re-enter the very accessor
    // being assigned to whenever the home object also owns an accessor of the same name (infinite
    // recursion, e.g. an object-literal setter that does `super.x = v`). A static method's home is the
    // class itself, so GetSuperBase() there is the class's own (dynamic, possibly reassigned)
    // [[Prototype]] rather than the instance prototype chain used by `superProtoStart`.
    public void evalSuperMemberWrite(MemberExpression member, JsValue value, Environment env) {
        final var thisArg = env.resolveThis();
        final var key = interp.memberKey(member, env);
        if (thisArg instanceof JsClass cls) {
            final var base = cls.getProto();
            if (base == null) {
                throw new TypeErrorException("Cannot set properties of null (setting '" + key + "')");
            }
            if (!interp.members().setMember(base, key, value, thisArg)) {
                throw new TypeErrorException("Cannot assign to read only property 'super." + key + "'");
            }
            return;
        }
        final var start = superProtoStart(env, true);
        final var target = start == null ? interp.intrinsics().objectProto() : start;
        if (!interp.members().setMember(target, key, value, thisArg)) {
            throw new TypeErrorException("Cannot assign to read only property 'super." + key + "'");
        }
    }

    // The plain-assignment ("=") entry point for `super.x = expr`/`super[expr1] = expr2`: unlike
    // `evalSuperMemberWrite` (used once the right-hand side is already known, e.g. `super.x += 1` or
    // `super.x++`), here the right-hand side must be evaluated *between* resolving the reference (the
    // super base, plus the raw - not yet ToPropertyKey'd - key) and performing the actual [[Set]], per
    // AssignmentExpression's left-to-right evaluation and PutValue's own deferred ToPropertyKey/
    // RequireObjectCoercible ordering.
    public JsValue evalSuperMemberAssign(MemberExpression member, Expression valueExpr, Environment env) {
        final var thisArg = env.resolveThis();
        if (thisArg instanceof JsClass cls) {
            final var rawKey = interp.memberKeyValue(member, env);
            final var base = cls.getProto();
            final var value = interp.eval(valueExpr, env);
            final var key = JsCoercion.toStr(rawKey, interp.ops());
            if (base == null) {
                throw new TypeErrorException("Cannot set properties of null (setting '" + key + "')");
            }
            if (!interp.members().setMember(base, key, value, thisArg)) {
                throw new TypeErrorException("Cannot assign to read only property 'super." + key + "'");
            }
            return value;
        }
        final var start = superProtoStart(env, false);
        final var rawKey = interp.memberKeyValue(member, env);
        final var value = interp.eval(valueExpr, env);
        final var key = JsCoercion.toStr(rawKey, interp.ops());
        final var target = start == null ? interp.intrinsics().objectProto() : start;
        if (!interp.members().setMember(target, key, value, thisArg)) {
            throw new TypeErrorException("Cannot assign to read only property 'super." + key + "'");
        }
        return value;
    }

    public JsValue evalSuperMemberRead(MemberExpression member, Environment env) {
        final var thisArg = env.resolveThis();
        final var start = thisArg instanceof JsClass cls ? cls.getProto() : superProtoStart(env);
        final var rawKey = interp.memberKeyValue(member, env);
        return superRead(start, rawKey, thisArg);
    }

    private JsValue superProtoRead(JsValue start, String key, JsValue thisArg) {
        if (start == null) {
            throw new TypeErrorException("Cannot read properties of null (reading '" + key + "')");
        }
        final var found = interp.members().chainMember(start, key, thisArg);
        return found == null ? JsUndefined.getInstance() : found;
    }

    // GetSuperBase: the home object's [[Prototype]]. A base class or a plain object literal has none
    // of its own, so the chain starts at Object.prototype; `extends null` genuinely has no base, which
    // RequireObjectCoercible rejects outright. A plain object literal/method whose own [[Prototype]]
    // was explicitly nulled (Object.setPrototypeOf(obj, null)) is the same case: GetSuperBase() is
    // null, not the synthesised Object.prototype - JsObject.isProtoExplicitlyNull distinguishes that
    // from "never set", mirroring the fix MemberEvaluator.getObjectMember applies for plain member
    // reads.
    private JsValue superProtoStart(Environment env) {
        return superProtoStart(env, true);
    }

    // `throwOnNullHeritage` lets a caller that must sequence its own side effects (RHS evaluation)
    // after resolving the base but before RequireObjectCoercible - i.e. a plain assignment's PutValue -
    // defer the throw instead of raising it the moment the base is resolved.
    private JsValue superProtoStart(Environment env, boolean throwOnNullHeritage) {
        final var home = env.resolveHomeClass();
        if (home instanceof JsObject object) {
            if (object.isProtoExplicitlyNull()) {
                if (throwOnNullHeritage) {
                    throw new TypeErrorException("Cannot read properties of null (reading '" + "')");
                }
                return null;
            }
            final var proto = object.getProto();
            return proto == null ? interp.intrinsics().objectProto() : proto;
        }
        if (home instanceof JsClass cls) {
            if (cls.hasNullHeritage()) {
                if (throwOnNullHeritage) {
                    throw new TypeErrorException("Cannot read properties of null (reading '" + "')");
                }
                return null;
            }
            final var start = cls.getPrototype().getProto();
            return start == null ? interp.intrinsics().objectProto() : start;
        }
        throw new SyntaxErrorException("'super' keyword unexpected here: ");
    }

    private JsClass superHomeClass(Environment env) {
        if (env.resolveHomeClass() instanceof JsClass cls && cls.isDerived()) {
            return cls;
        }
        throw new SyntaxErrorException("'super' keyword unexpected here");
    }

    public JsValue evalBrandCheck(PrivateIdentifier priv, JsValue target, Environment env) {
        if (!isObjectLike(target)) {
            throw new TypeErrorException(
                    "Cannot use 'in' operator to search for '#" + priv.getName() + "' in a non-object");
        }
        final var owner = env.resolvePrivateClass(priv.getName());
        if (owner == null) {
            return JsBoolean.FALSE;
        }
        final var privateName = owner.privateNameFor(priv.getName());
        if (target instanceof JsClass cls) {
            return JsBoolean.of(cls == owner && owner.declaresStaticPrivate(privateName));
        }
        if (target instanceof JsObject object) {
            if (object.hasPrivate(privateName)) {
                return JsBoolean.TRUE;
            }
            if (owner.declaresPrivate(privateName)) {
                return JsBoolean.of(object.hasPrivateBrand(owner));
            }
        }
        return JsBoolean.FALSE;
    }

    public JsValue evalInstanceof(JsValue left, JsValue right) {
        if (isObjectLike(right)) {
            final var hasInstance = interp.getMemberByKey(right, JsSymbol.HAS_INSTANCE);
            if (isCallable(hasInstance) && !interp.intrinsics().isDefaultHasInstance(hasInstance)) {
                return JsBoolean.of(JsCoercion.toBoolean(interp.callValue(hasInstance, right, List.of(left))));
            }
        }
        if (right instanceof JsNativeFunction bound && bound.isBound()) {
            return evalInstanceof(left, bound.getBoundTarget());
        }
        if (!isSpecCallable(right)) {
            throw new TypeErrorException("Right-hand side of 'instanceof' is not callable");
        }
        // OrdinaryHasInstance rejects a non-object left operand before it reads the prototype, so a
        // primitive never triggers the accessor or the non-object-prototype TypeError.
        if (!isObjectLike(left)) {
            return JsBoolean.FALSE;
        }
        final var prototype = declaredPrototype(right);
        return switch (right) {
            case JsClass cls -> JsBoolean.of(isInstanceOfClass(left, cls, prototype));
            case JsFunction ignored -> JsBoolean.of(hasInPrototypeChain(left, prototype));
            default -> JsBoolean.of(isInstanceOfNative(left, prototype));
        };
    }

    // OrdinaryHasInstance's default behavior is reached only when IsCallable(C) is true; the spec
    // notion of "callable" is broader than InterpreterUtils.isCallable (which excludes JsClass, since
    // a class is never callable via a plain call) and also covers %Function.prototype% itself - a
    // plain JsObject that Interpreter.callValue special-cases as callable (see the comment there) so
    // it can stay exposed as a JsObject rather than a JsFunction/JsNativeFunction. Without this,
    // `0 instanceof Function.prototype` and friends hit the "not callable" TypeError instead of
    // OrdinaryHasInstance's own `Type(O) is not Object -> false` / prototype-getter semantics.
    private boolean isSpecCallable(JsValue value) {
        return value instanceof JsClass || isCallable(value)
                || (value instanceof JsObject object && object == interp.intrinsics().functionProto());
    }

    // OrdinaryHasInstance's own prototype-chain walk (step 6): [[GetPrototypeOf]] on a proxy runs
    // its trap (and enforces the extensible-target invariant), which a raw JsValue.getProto() read
    // can never see, so a proxy anywhere in the chain - including as `left` itself - has to be
    // dereferenced through the InterpreterOps seam rather than InterpreterUtils.hasInPrototypeChain.
    private boolean hasInPrototypeChain(JsValue left, JsValue prototype) {
        var link = left;
        for (var proto = protoOf(link); proto != null; proto = protoOf(link)) {
            if (proto == prototype) {
                return true;
            }
            link = proto;
        }
        return endsInIntrinsicDefault(link, prototype);
    }

    // A JsObject whose own [[Prototype]] was never resolved (e.g. JsFunction.getPrototype's lazily
    // created `prototype` object, which has no reason to know about the realm's Object.prototype) is
    // the same "never set" vs. "deliberately null" ambiguity MemberEvaluator.getObjectMember resolves
    // for property reads - it implicitly inherits the realm's type-default, so the raw walk above
    // must not treat a merely-unset terminus as the true end of the chain. `Object.create(null)`
    // remains a genuine dead end (isProtoExplicitlyNull is true there). One hop is enough: protoFor
    // already answers objectProto for objectProto itself, so a second hop would never terminate -
    // guarded by `fallback != link` rather than looping through protoOf again.
    private boolean endsInIntrinsicDefault(JsValue link, JsValue prototype) {
        if (!(link instanceof JsObject object) || object.isProtoExplicitlyNull()) {
            return false;
        }
        final var fallback = interp.intrinsics().protoFor(object);
        return fallback != object && (fallback == prototype || hasInPrototypeChain(fallback, prototype));
    }

    private JsValue protoOf(JsValue value) {
        return value instanceof JsProxy ? interp.ops().getPrototypeOf(value) : value.getProto();
    }

    // OrdinaryHasInstance reads Get(C, "prototype"), not the internal slot, so an accessor-valued
    // `prototype` runs and a non-object result is a TypeError instead of a silent false.
    private JsValue declaredPrototype(JsValue constructor) {
        final var prototype = interp.getMember(constructor, "prototype");
        if (isObjectLike(prototype)) {
            return prototype;
        }
        throw new TypeErrorException("Function has a non-object prototype in an instanceof check");
    }

    // A native-heritage class's instances are always constructed as a JsObject wrapper (klass +
    // proto set to the class's own prototype, per ClassEvaluator.construct), so the klass/proto
    // checks above already cover them. Walking the native super's intrinsic chain here would be
    // wrong: every plain `new Set()` shares that same intrinsic Set.prototype, so it would make
    // `new Set() instanceof MySet` true for any class MySet extends Set.
    private boolean isInstanceOfClass(JsValue left, JsClass cls, JsValue prototype) {
        if (left instanceof JsObject object && object.getKlass() != null && object.getKlass().isSubclassOf(cls)) {
            return true;
        }
        return hasInPrototypeChain(left, prototype);
    }

    // A non-JsObject runtime value has no own prototype link, so an object-like one (array, map,
    // function, …) is matched against the realm's intrinsic chain instead; primitives never match.
    private boolean isInstanceOfNative(JsValue left, JsValue prototype) {
        if (prototype == null) {
            return false;
        }
        if (hasInPrototypeChain(left, prototype)) {
            return true;
        }
        if (left instanceof JsObject || isPrimitiveValue(left)) {
            return false;
        }
        for (var proto = (JsValue) interp.intrinsics().protoFor(left); proto != null; proto = proto.getProto()) {
            if (proto == prototype) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrimitiveValue(JsValue value) {
        return value instanceof JsString || value instanceof JsNumber || value instanceof JsBoolean
                || value instanceof JsBigInt || value instanceof JsSymbol || value instanceof JsNull
                || value instanceof JsUndefined;
    }
}

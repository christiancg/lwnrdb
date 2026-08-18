package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.hasInPrototypeChain;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.staticKeyName;

import java.util.ArrayList;
import java.util.List;
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

    public ClassEvaluator(Interpreter interp) {
        this.interp = interp;
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
        JsClass superClass = null;
        JsValue superConstructor = null;
        var nullHeritage = false;
        if (superClassExpr != null) {
            final var resolved = interp.eval(superClassExpr, env);
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
        final var classScope = env.child();
        final var methodScope = classScope.child();
        final var name = id == null ? null : id.getName();
        final var cls = new JsClass(name, superClass, methodScope);
        if (nullHeritage) {
            cls.markNullHeritage();
        }
        if (superConstructor != null) {
            // ClassDefinitionEvaluation reads Get(superclass, "prototype"), so an accessor-valued or
            // assigned `prototype` on the heritage is observed here; anything that is neither an
            // object nor null is a TypeError before the body is evaluated.
            final var parentPrototype = interp.getMember(superConstructor, "prototype");
            if (!isObjectLike(parentPrototype) && !(parentPrototype instanceof JsNull)) {
                throw new TypeErrorException("Class extends value does not have valid prototype property "
                        + JsCoercion.toStr(parentPrototype));
            }
            cls.setSuperConstructor(superConstructor, parentPrototype instanceof JsNull ? null : parentPrototype);
        }
        methodScope.defineHomeClass(cls);
        declarePrivateNames(cls, body);
        classScope.definePrivateEnvironment(cls);
        if (name != null) {
            classScope.declareLexical(name, "const");
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
        if (field.isStatic() && isNamed(key, "prototype")) {
            throw new TypeErrorException("Classes may not have a static field named 'prototype'");
        }
        if (!field.isStatic() && isNamed(key, "constructor")) {
            throw new TypeErrorException("Classes may not have a field named 'constructor'");
        }
        return key;
    }

    private static boolean isNamed(JsValue key, String name) {
        return key instanceof JsString string && name.equals(string.getValue());
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
            if (method.isStatic() && isNamed(keyValue, "prototype")) {
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
                final var blockEnv = staticScope.child();
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
        cls.setStaticProp(key, value);
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
            applySuperConstructor(superCtor, instance, args, newTarget);
        } else if (cls.hasNullHeritage()) {
            throw new TypeErrorException("Super constructor null of " + cls.getName() + " is not a constructor");
        } else if (cls.getSuperClass() != null) {
            self = callConstructorChain(cls.getSuperClass(), instance, args, newTarget);
        }
        if (self instanceof JsObject target) {
            initFields(cls, target);
        }
        if (constructor != null) {
            return overrideOrInstance(interp.callFunction(constructor, self, args, newTarget), self);
        }
        return self;
    }

    private static JsValue overrideOrInstance(JsValue returned, JsValue instance) {
        return isObjectLike(returned) ? returned : instance;
    }

    // A non-class heritage: a user function runs directly on the instance under construction (its body
    // writes through `this`), a builtin goes through the copy path that keeps its internal state, and
    // anything else constructible is constructed and its own state adopted.
    private void applySuperConstructor(JsValue superCtor, JsObject instance, List<JsValue> args, JsValue newTarget) {
        switch (superCtor) {
            case JsNativeFunction nativeSuper -> applyNativeSuper(nativeSuper, instance, args);
            case JsFunction function -> interp.callFunction(function, instance, args, newTarget);
            default -> adoptConstructed(interp.construct(superCtor, args, newTarget), instance);
        }
    }

    // A native super has no method tables to chain into, so its result's own state is copied onto the
    // instance and the instance is linked to the native prototype for method lookup and instanceof.
    // The instance under construction is passed as thisArg (a plain `new NativeCtor()` passes
    // JsUndefined instead), giving an abstract native constructor like Iterator's a signal to
    // distinguish a super() call from a subclass from direct construction.
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

    private void initFields(JsClass cls, JsObject instance) {
        if (!instance.addPrivateBrand(cls) && cls.hasPrivateInstanceBrand()) {
            throw new TypeErrorException(
                    "Cannot initialize the private members of " + cls.getName() + " twice on the same object");
        }
        for (final var entry : cls.getInstanceFields()) {
            final var field = entry.definition();
            final var fieldScope = cls.getMethodScope().child();
            fieldScope.defineThis(instance);
            final var value = field.getValue() == null
                    ? JsUndefined.getInstance()
                    : interp.eval(field.getValue(), fieldScope);
            if (field.getKey() instanceof PrivateIdentifier priv) {
                nameField(field, value, "#" + priv.getName());
                if (!instance.addPrivate(cls.declarePrivateName(priv.getName()), value)) {
                    throw new TypeErrorException("Cannot add private field #" + priv.getName() + " to this object");
                }
            } else if (entry.key() instanceof JsSymbol symbol) {
                nameField(field, value, symbolMethodName(symbol));
                instance.setSymbol(symbol, value);
            } else {
                final var key = entry.key() == null
                        ? staticKeyName(field.getKey())
                        : ((JsString) entry.key()).getValue();
                nameField(field, value, key);
                instance.set(key, value);
            }
        }
    }

    public JsValue evalSuperCall(CallExpression call, Environment env) {
        final var home = superHomeClass(env);
        final var thisValue = env.resolveThisBeforeSuper();
        if (!(thisValue instanceof JsObject instance)) {
            throw new TypeErrorException("'super' call outside of a constructor");
        }
        if (env.isThisInitialized()) {
            throw new ReferenceErrorException("Super constructor may only be called once");
        }
        final var args = interp.evalArguments(call.getArguments(), env);
        var self = (JsValue) instance;
        if (home.hasNullHeritage()) {
            throw new TypeErrorException("Super constructor null of " + home.getName() + " is not a constructor");
        }
        if (home.getSuperClass() == null) {
            applySuperConstructor(home.getSuperConstructor(), instance, args, env.resolveNewTarget());
        } else {
            self = callConstructorChain(home.getSuperClass(), instance, args, env.resolveNewTarget());
        }
        // A base constructor that returns an object becomes this derived constructor's `this`, so the
        // derived class's fields and brand land on that object.
        if (self != instance) {
            env.replaceThis(self);
        }
        env.markThisInitialized();
        if (self instanceof JsObject target) {
            initFields(home, target);
        }
        return JsUndefined.getInstance();
    }

    public JsValue evalSuperMemberCall(MemberExpression member, CallExpression call, Environment env) {
        final var thisArg = env.resolveThis();
        final var key = interp.memberKey(member, env);
        final var args = interp.evalArguments(call.getArguments(), env);
        if (thisArg instanceof JsClass) {
            final var parent = superMemberParent(superHomeClass(env), key);
            final var method = parent.findStaticMethod(key);
            if (method != null) {
                return interp.callFunction(method, thisArg, args);
            }
            final var getter = parent.findStaticGetter(key);
            if (getter != null) {
                return interp.callValue(interp.callFunction(getter, thisArg, List.of()), thisArg, args);
            }
            throw new TypeErrorException("(intermediate value).super." + key + " is not a function");
        }
        final var value = superProtoRead(env, key, thisArg);
        if (isCallable(value)) {
            return interp.callValue(value, thisArg, args);
        }
        throw new TypeErrorException("(intermediate value).super." + key + " is not a function");
    }

    // PutValue on a super reference is Set(GetSuperBase(), key, value, thisValue): the receiver is
    // `this`, so an absent setter on the super chain writes an own property on the instance.
    public void evalSuperMemberWrite(MemberExpression member, JsValue value, Environment env) {
        final var thisArg = env.resolveThis();
        superProtoStart(env, "");
        final var key = interp.memberKey(member, env);
        if (!interp.members().setMember(thisArg, key, value, thisArg)) {
            throw new TypeErrorException("Cannot assign to read only property 'super." + key + "'");
        }
    }

    public JsValue evalSuperMemberRead(MemberExpression member, Environment env) {
        final var thisArg = env.resolveThis();
        superProtoStart(env, "");
        final var key = interp.memberKey(member, env);
        if (thisArg instanceof JsClass) {
            final var parent = superMemberParent(superHomeClass(env), key);
            final var getter = parent.findStaticGetter(key);
            if (getter != null) {
                return interp.callFunction(getter, thisArg, List.of());
            }
            final var method = parent.findStaticMethod(key);
            if (method != null) {
                return method;
            }
            return interp.getStaticMember(parent, key);
        }
        return superProtoRead(env, key, thisArg);
    }

    private JsValue superProtoRead(Environment env, String key, JsValue thisArg) {
        final var start = superProtoStart(env, key);
        if (start == null) {
            return JsUndefined.getInstance();
        }
        final var found = interp.members().chainMember(start, key, thisArg);
        return found == null ? JsUndefined.getInstance() : found;
    }

    // GetSuperBase: the home object's [[Prototype]]. A base class or a plain object literal has none
    // of its own, so the chain starts at Object.prototype; `extends null` genuinely has no base.
    private JsValue superProtoStart(Environment env, String key) {
        final var home = env.resolveHomeClass();
        if (home instanceof JsObject object) {
            final var proto = object.getProto();
            return proto == null ? interp.intrinsics().objectProto() : proto;
        }
        if (home instanceof JsClass cls) {
            if (cls.hasNullHeritage()) {
                return null;
            }
            final var start = cls.getPrototype().getProto();
            return start == null ? interp.intrinsics().objectProto() : start;
        }
        throw new SyntaxErrorException("'super' keyword unexpected here: " + key);
    }

    private JsClass superHomeClass(Environment env) {
        if (env.resolveHomeClass() instanceof JsClass cls && cls.isDerived()) {
            return cls;
        }
        throw new SyntaxErrorException("'super' keyword unexpected here");
    }

    // A static `super.x` reads through the heritage class's own statics; a non-class heritage has no
    // static tables to chain into.
    private JsClass superMemberParent(JsClass home, String key) {
        final var parent = home.getSuperClass();
        if (parent == null) {
            throw new TypeErrorException(
                    "super." + key + " is not available: " + home.getName() + " has no class superclass");
        }
        return parent;
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
        if (!(right instanceof JsClass) && !(right instanceof JsFunction) && !(right instanceof JsNativeFunction)) {
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

    // OrdinaryHasInstance reads Get(C, "prototype"), not the internal slot, so an accessor-valued
    // `prototype` runs and a non-object result is a TypeError instead of a silent false.
    private JsValue declaredPrototype(JsValue constructor) {
        final var prototype = interp.getMember(constructor, "prototype");
        if (isObjectLike(prototype)) {
            return prototype;
        }
        throw new TypeErrorException("Function has a non-object prototype in an instanceof check");
    }

    private boolean isInstanceOfClass(JsValue left, JsClass cls, JsValue prototype) {
        if (left instanceof JsObject object && object.getKlass() != null && object.getKlass().isSubclassOf(cls)) {
            return true;
        }
        if (hasInPrototypeChain(left, prototype)) {
            return true;
        }
        final var nativeSuper = cls.findNativeSuperClass();
        return nativeSuper != null && isInstanceOfNative(left, nativeSuper.getPrototype());
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

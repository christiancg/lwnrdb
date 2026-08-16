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
import org.techhouse.simplejs.nodes.JsNode;
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
        JsNativeFunction nativeSuperClass = null;
        if (superClassExpr != null) {
            final var resolved = interp.eval(superClassExpr, env);
            if (resolved instanceof JsClass sc) {
                superClass = sc;
            } else if (resolved instanceof JsNativeFunction nf && nf.getPrototype() != null) {
                nativeSuperClass = nf;
            } else {
                throw new TypeErrorException(
                        "Class extends value " + JsCoercion.toStr(resolved) + " is not a constructor or null");
            }
        }
        final var classScope = env.child();
        final var methodScope = classScope.child();
        final var name = id == null ? null : id.getName();
        final var cls = new JsClass(name, superClass, methodScope);
        cls.setNativeSuperClass(nativeSuperClass);
        methodScope.defineHomeClass(cls);
        if (name != null) {
            classScope.declareLexical(name, "const");
            classScope.initialize(name, cls);
        }
        final var staticInit = new ArrayList<JsNode>();
        for (final var member : body.getMembers()) {
            switch (member) {
                case MethodDefinition method -> installMethod(cls, method, classScope);
                case FieldDefinition field -> {
                    if (field.isStatic()) {
                        staticInit.add(field);
                    } else {
                        cls.addInstanceField(field);
                    }
                }
                case StaticBlock block -> staticInit.add(block);
                default -> throw new UnsupportedNodeException(member.getType().name());
            }
        }
        runStaticInit(cls, staticInit);
        return cls;
    }

    private void installMethod(JsClass cls, MethodDefinition method, Environment classScope) {
        final var value = method.getValue();
        final var fn = interp.makeFunction(null, value.getParams(), value.getBody(), false, false, value.isAsync(),
                value.isGenerator(), cls.getMethodScope());
        final var kind = method.getKind();
        if ("constructor".equals(kind)) {
            if (cls.getSuperClass() != null || cls.getNativeSuperClass() != null) {
                fn.markDerivedConstructor();
            }
            cls.setConstructor(fn);
            return;
        }
        if (!fn.isGenerator()) {
            fn.markMethod();
        }
        if (method.getKey() instanceof PrivateIdentifier priv) {
            fn.setInferredName(accessorName(kind, "#" + priv.getName()));
            if (method.isStatic()) {
                cls.addPrivateStaticMethod(priv.getName(), kind, fn);
            } else {
                cls.addPrivateInstanceMethod(priv.getName(), kind, fn);
            }
            return;
        }
        if (method.isComputed()) {
            final var keyValue = interp.eval(method.getKey(), classScope);
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

    private void runStaticInit(JsClass cls, List<JsNode> staticInit) {
        final var staticScope = cls.getMethodScope().child();
        staticScope.defineThis(cls);
        for (final var node : staticInit) {
            if (node instanceof FieldDefinition field) {
                final var value = field.getValue() == null
                        ? JsUndefined.getInstance()
                        : interp.eval(field.getValue(), staticScope);
                if (field.getKey() instanceof PrivateIdentifier priv) {
                    nameField(field, value, "#" + priv.getName());
                    cls.setPrivateStaticField(priv.getName(), value);
                } else if (field.isComputed()) {
                    final var keyValue = interp.eval(field.getKey(), staticScope);
                    if (keyValue instanceof JsSymbol symbol) {
                        nameField(field, value, symbolMethodName(symbol));
                        cls.setStaticSymbolProp(symbol, value);
                    } else {
                        final var key = JsCoercion.toStr(keyValue);
                        nameField(field, value, key);
                        cls.setStaticProp(key, value);
                    }
                } else {
                    final var key = staticKeyName(field.getKey());
                    nameField(field, value, key);
                    cls.setStaticProp(key, value);
                }
            } else {
                final var block = (StaticBlock) node;
                final var blockEnv = staticScope.child();
                interp.hoist(block.getBody(), blockEnv);
                for (final var statement : block.getBody()) {
                    if (!interp.evalStatement(statement, blockEnv).isNormal()) {
                        break;
                    }
                }
            }
        }
    }

    public JsValue construct(JsClass cls, List<JsValue> args, JsValue newTarget) {
        final var instance = new JsObject();
        instance.setKlass(cls);
        // OrdinaryCreateFromConstructor: an ordinary Get(newTarget, "prototype"), so an
        // accessor-valued or throwing `prototype` behaves like any other property read.
        instance.setProto(interp.getMemberByKey(newTarget, new JsString("prototype")) instanceof JsObject proto
                ? proto
                : cls.getPrototype());
        callConstructorChain(cls, instance, args, newTarget);
        return instance;
    }

    private void callConstructorChain(JsClass cls, JsObject instance, List<JsValue> args, JsValue newTarget) {
        final var constructor = cls.getConstructor();
        if (cls.getSuperClass() == null) {
            if (cls.getNativeSuperClass() != null) {
                applyNativeSuper(cls.getNativeSuperClass(), instance, args);
            }
            initFields(cls, instance);
            if (constructor != null) {
                interp.callFunction(constructor, instance, args, newTarget);
            }
        } else if (constructor == null) {
            callConstructorChain(cls.getSuperClass(), instance, args, newTarget);
            initFields(cls, instance);
        } else {
            interp.callFunction(constructor, instance, args, newTarget);
        }
    }

    // A native super has no method tables to chain into, so its result's own state is copied onto the
    // instance and the instance is linked to the native prototype for method lookup and instanceof.
    // The instance under construction is passed as thisArg (a plain `new NativeCtor()` passes
    // JsUndefined instead), giving an abstract native constructor like Iterator's a signal to
    // distinguish a super() call from a subclass from direct construction.
    private void applyNativeSuper(JsNativeFunction nativeSuper, JsObject instance, List<JsValue> args) {
        final var produced = nativeSuper.invoke(instance, args);
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
        instance.addPrivateBrand(cls);
        for (final var field : cls.getInstanceFields()) {
            final var fieldScope = cls.getMethodScope().child();
            fieldScope.defineThis(instance);
            final var value = field.getValue() == null
                    ? JsUndefined.getInstance()
                    : interp.eval(field.getValue(), fieldScope);
            if (field.getKey() instanceof PrivateIdentifier priv) {
                nameField(field, value, "#" + priv.getName());
                instance.setPrivate(priv.getName(), value);
            } else if (field.isComputed()) {
                final var keyValue = interp.eval(field.getKey(), fieldScope);
                if (keyValue instanceof JsSymbol symbol) {
                    nameField(field, value, symbolMethodName(symbol));
                    instance.setSymbol(symbol, value);
                } else {
                    final var key = JsCoercion.toStr(keyValue);
                    nameField(field, value, key);
                    instance.set(key, value);
                }
            } else {
                final var key = staticKeyName(field.getKey());
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
        if (home.getSuperClass() == null) {
            applyNativeSuper(home.getNativeSuperClass(), instance, args);
        } else {
            callConstructorChain(home.getSuperClass(), instance, args, env.resolveNewTarget());
        }
        env.markThisInitialized();
        initFields(home, instance);
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

    public JsValue evalSuperMemberRead(MemberExpression member, Environment env) {
        final var thisArg = env.resolveThis();
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
        for (var proto = superProtoStart(env, key); proto != null; proto = proto.getProto()) {
            final var getter = proto.getAccessorGetter(key);
            if (getter != null) {
                return interp.callValue(getter, thisArg, List.of());
            }
            if (proto.has(key)) {
                return proto.get(key);
            }
        }
        return JsUndefined.getInstance();
    }

    private JsObject superProtoStart(Environment env, String key) {
        final var home = env.resolveHomeClass();
        if (home instanceof JsObject object) {
            return object.getProto();
        }
        final var cls = superHomeClass(env);
        superMemberParent(cls, key);
        return cls.getPrototype().getProto();
    }

    private JsClass superHomeClass(Environment env) {
        if (env.resolveHomeClass() instanceof JsClass cls
                && (cls.getSuperClass() != null || cls.getNativeSuperClass() != null)) {
            return cls;
        }
        throw new SyntaxErrorException("'super' keyword unexpected here");
    }

    private JsClass superMemberParent(JsClass home, String key) {
        final var parent = home.getSuperClass();
        if (parent == null) {
            throw new TypeErrorException("super." + key + " is not available: " + home.getNativeSuperClass().getName()
                    + " is a builtin superclass with no chainable methods");
        }
        return parent;
    }

    public JsValue evalBrandCheck(PrivateIdentifier priv, JsValue target, Environment env) {
        final var name = priv.getName();
        if (target instanceof JsClass cls) {
            return JsBoolean.of(env.resolveHomeClass() instanceof JsClass home && home.declaresStaticPrivate(name)
                    && cls.declaresStaticPrivate(name));
        }
        if (target instanceof JsObject object) {
            if (object.hasPrivate(name)) {
                return JsBoolean.TRUE;
            }
            if (env.resolveHomeClass() instanceof JsClass cls && cls.declaresPrivate(name)) {
                return JsBoolean.of(object.hasPrivateBrand(cls));
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
    private JsObject declaredPrototype(JsValue constructor) {
        final var prototype = interp.getMember(constructor, "prototype");
        if (prototype instanceof JsObject object) {
            return object;
        }
        throw new TypeErrorException("Function has a non-object prototype in an instanceof check");
    }

    private boolean isInstanceOfClass(JsValue left, JsClass cls, JsObject prototype) {
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
    private boolean isInstanceOfNative(JsValue left, JsObject prototype) {
        if (prototype == null) {
            return false;
        }
        if (hasInPrototypeChain(left, prototype)) {
            return true;
        }
        if (left instanceof JsObject || isPrimitiveValue(left)) {
            return false;
        }
        for (var proto = interp.intrinsics().protoFor(left); proto != null; proto = proto.getProto()) {
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

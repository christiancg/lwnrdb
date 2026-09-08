package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ClassEvaluator {
    private final Interpreter interp;

    private final Map<JsValue, JsObject> privateStorageBridges = new IdentityHashMap<>();

    private final SuperAccess supers;

    private final ClassConstruction construction;

    private final ClassMemberInstaller installer;

    public ClassEvaluator(Interpreter interp) {
        this.interp = interp;
        this.installer = new ClassMemberInstaller(interp);
        this.construction = new ClassConstruction(interp, this);
        this.supers = new SuperAccess(interp, this);
    }

    public JsObject resolvePrivateStorage(JsValue target) {
        if (target instanceof JsObject object) {
            return object;
        }
        return privateStorageBridges.get(target);
    }

    public Completion evalClassDeclaration(ClassDeclaration declaration, Environment env) {
        final var cls = buildClass(declaration, declaration.getId(), declaration.getSuperClass(), declaration.getBody(),
                env, null);
        final var name = declaration.getId().getName();
        env.declareLexical(name, "let");
        env.initialize(name, cls);
        return Completion.empty();
    }

    public JsValue evalClassExpression(ClassExpression expression, Environment env) {
        return buildClass(expression, expression.getId(), expression.getSuperClass(), expression.getBody(), env, null);
    }

    public JsValue evalClassExpression(ClassExpression expression, Environment env, String inferredName) {
        return buildClass(expression, expression.getId(), expression.getSuperClass(), expression.getBody(), env,
                inferredName);
    }

    private JsClass buildClass(JsNode origin, Identifier id, Expression superClassExpr, ClassBody body, Environment env,
            String inferredName) {
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
        cls.setSourceText(origin.getSourceText());
        if (nullHeritage) {
            cls.markNullHeritage();
            cls.setProto(interp.intrinsics().functionProto);
        } else if (superConstructor != null) {
            final var parentPrototype = interp.getMember(superConstructor, "prototype");
            if (!isObjectLike(parentPrototype) && !(parentPrototype instanceof JsNull)) {
                throw new TypeErrorException("Class extends value does not have valid prototype property "
                        + JsCoercion.toStr(parentPrototype));
            }
            cls.setSuperConstructor(superConstructor, parentPrototype instanceof JsNull ? null : parentPrototype);
        } else if (superClass == null) {
            cls.setProto(interp.intrinsics().functionProto);
            cls.getPrototype().setProto(interp.intrinsics().objectProto);
        }
        methodScope.defineHomeClass(cls);
        ClassMemberInstaller.declarePrivateNames(cls, body);
        classScope.definePrivateEnvironment(cls);
        if (name != null) {
            classScope.initialize(name, cls);
        }
        final var staticInit = new ArrayList<StaticEntry>();
        for (final var member : body.getMembers()) {
            switch (member) {
                case MethodDefinition method -> installer.installMethod(cls, method, classScope);
                case FieldDefinition field -> {
                    final var key = installer.fieldKey(field, classScope);
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
        if (inferredName != null) {
            cls.setInferredName(inferredName);
        }
        installer.runStaticInit(cls, staticInit);
        return cls;
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

    private boolean isSpecCallable(JsValue value) {
        return value instanceof JsClass || isCallable(value)
                || (value instanceof JsObject object && object == interp.intrinsics().functionProto);
    }

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
        return hasInPrototypeChain(left, prototype);
    }

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
    public JsValue evalSuperCall(CallExpression call, Environment env) {
        return supers.evalSuperCall(call, env);
    }

    public JsValue evalSuperMemberCall(MemberExpression member, CallExpression call, Environment env) {
        return supers.evalSuperMemberCall(member, call, env);
    }

    public JsValue evalSuperMemberRead(MemberExpression member, Environment env) {
        return supers.evalSuperMemberRead(member, env);
    }

    public JsValue evalSuperMemberAssign(MemberExpression member, Expression valueExpr, Environment env) {
        return supers.evalSuperMemberAssign(member, valueExpr, env);
    }

    public void evalSuperMemberWrite(MemberExpression member, JsValue value, Environment env) {
        supers.evalSuperMemberWrite(member, value, env);
    }

    public JsValue construct(JsClass cls, List<JsValue> args, JsValue newTarget) {
        return construction.construct(cls, args, newTarget);
    }

    void bridgePrivateStorage(JsValue layer, JsObject storage) {
        privateStorageBridges.put(layer, storage);
    }

    public JsValue applySuperConstructor(JsValue superCtor, JsObject instance, List<JsValue> args, JsValue newTarget) {
        return construction.applySuperConstructor(superCtor, instance, args, newTarget);
    }

    public JsValue callConstructorChain(JsClass cls, JsObject instance, List<JsValue> args, JsValue newTarget) {
        return construction.callConstructorChain(cls, instance, args, newTarget);
    }

    public JsObject unwrapForFieldInit(JsValue self) {
        return construction.unwrapForFieldInit(self);
    }

    public void initFields(JsClass cls, JsValue self, JsObject storage) {
        construction.initFields(cls, self, storage);
    }
}

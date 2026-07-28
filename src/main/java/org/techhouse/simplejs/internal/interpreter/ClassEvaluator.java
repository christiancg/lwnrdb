package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.hasInPrototypeChain;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.staticKeyName;

import java.util.ArrayList;
import java.util.List;
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
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
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
        if (superClassExpr != null) {
            final var resolved = interp.eval(superClassExpr, env);
            if (!(resolved instanceof JsClass sc)) {
                throw new TypeErrorException(
                        "Class extends value " + JsCoercion.toStr(resolved) + " is not a constructor or null");
            }
            superClass = sc;
        }
        final var classScope = env.child();
        final var methodScope = classScope.child();
        final var name = id == null ? null : id.getName();
        final var cls = new JsClass(name, superClass, methodScope);
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
            cls.setConstructor(fn);
            return;
        }
        if (method.getKey() instanceof PrivateIdentifier priv) {
            cls.addPrivateInstanceMethod(priv.getName(), kind, fn);
            return;
        }
        if (method.isComputed()) {
            final var keyValue = interp.eval(method.getKey(), classScope);
            if (keyValue instanceof JsSymbol symbol) {
                if (method.isStatic()) {
                    cls.addStaticSymbolMethod(symbol, kind, fn);
                } else {
                    cls.addInstanceSymbolMethod(symbol, kind, fn);
                }
                return;
            }
            installStringMethod(cls, method, kind, fn, JsCoercion.toStr(keyValue));
            return;
        }
        installStringMethod(cls, method, kind, fn, staticKeyName(method.getKey()));
    }

    private void installStringMethod(JsClass cls, MethodDefinition method, String kind, JsFunction fn, String key) {
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
                if (field.isComputed()) {
                    final var keyValue = interp.eval(field.getKey(), staticScope);
                    if (keyValue instanceof JsSymbol symbol) {
                        cls.setStaticSymbolProp(symbol, value);
                    } else {
                        cls.setStaticProp(JsCoercion.toStr(keyValue), value);
                    }
                } else {
                    cls.setStaticProp(staticKeyName(field.getKey()), value);
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

    public JsValue construct(JsClass cls, List<JsValue> args) {
        final var instance = new JsObject();
        instance.setKlass(cls);
        callConstructorChain(cls, instance, args);
        return instance;
    }

    private void callConstructorChain(JsClass cls, JsObject instance, List<JsValue> args) {
        final var constructor = cls.getConstructor();
        if (cls.getSuperClass() == null) {
            initFields(cls, instance);
            if (constructor != null) {
                interp.callFunction(constructor, instance, args);
            }
        } else if (constructor == null) {
            callConstructorChain(cls.getSuperClass(), instance, args);
            initFields(cls, instance);
        } else {
            interp.callFunction(constructor, instance, args);
        }
    }

    private void initFields(JsClass cls, JsObject instance) {
        for (final var field : cls.getInstanceFields()) {
            final var fieldScope = cls.getMethodScope().child();
            fieldScope.defineThis(instance);
            final var value = field.getValue() == null
                    ? JsUndefined.getInstance()
                    : interp.eval(field.getValue(), fieldScope);
            if (field.getKey() instanceof PrivateIdentifier priv) {
                instance.setPrivate(priv.getName(), value);
            } else if (field.isComputed()) {
                final var keyValue = interp.eval(field.getKey(), fieldScope);
                if (keyValue instanceof JsSymbol symbol) {
                    instance.setSymbol(symbol, value);
                } else {
                    instance.set(JsCoercion.toStr(keyValue), value);
                }
            } else {
                instance.set(staticKeyName(field.getKey()), value);
            }
        }
    }

    public JsValue evalSuperCall(CallExpression call, Environment env) {
        final var home = superHomeClass(env);
        final var thisValue = env.resolveThis();
        if (!(thisValue instanceof JsObject instance)) {
            throw new TypeErrorException("'super' call outside of a constructor");
        }
        final var args = interp.evalArguments(call.getArguments(), env);
        callConstructorChain(home.getSuperClass(), instance, args);
        initFields(home, instance);
        return JsUndefined.getInstance();
    }

    public JsValue evalSuperMemberCall(MemberExpression member, CallExpression call, Environment env) {
        final var home = superHomeClass(env);
        final var thisArg = env.resolveThis();
        final var parent = home.getSuperClass();
        final var key = interp.memberKey(member, env);
        final var args = interp.evalArguments(call.getArguments(), env);
        final var staticContext = thisArg instanceof JsClass;
        final var method = staticContext ? parent.findStaticMethod(key) : parent.findInstanceMethod(key);
        if (method != null) {
            return interp.callFunction(method, thisArg, args);
        }
        final var getter = staticContext ? parent.findStaticGetter(key) : parent.findInstanceGetter(key);
        if (getter != null) {
            return interp.callValue(interp.callFunction(getter, thisArg, List.of()), thisArg, args);
        }
        throw new TypeErrorException("(intermediate value).super." + key + " is not a function");
    }

    public JsValue evalSuperMemberRead(MemberExpression member, Environment env) {
        final var home = superHomeClass(env);
        final var thisArg = env.resolveThis();
        final var parent = home.getSuperClass();
        final var key = interp.memberKey(member, env);
        final var staticContext = thisArg instanceof JsClass;
        final var getter = staticContext ? parent.findStaticGetter(key) : parent.findInstanceGetter(key);
        if (getter != null) {
            return interp.callFunction(getter, thisArg, List.of());
        }
        final var method = staticContext ? parent.findStaticMethod(key) : parent.findInstanceMethod(key);
        if (method != null) {
            return method;
        }
        if (staticContext) {
            return interp.getStaticMember(parent, key);
        }
        return JsUndefined.getInstance();
    }

    private JsClass superHomeClass(Environment env) {
        if (env.resolveHomeClass() instanceof JsClass cls && cls.getSuperClass() != null) {
            return cls;
        }
        throw new SyntaxErrorException("'super' keyword unexpected here");
    }

    public JsValue evalBrandCheck(PrivateIdentifier priv, JsValue target, Environment env) {
        final var name = priv.getName();
        if (target instanceof JsObject object) {
            if (object.hasPrivate(name)) {
                return JsBoolean.TRUE;
            }
            if (env.resolveHomeClass() instanceof JsClass cls && cls.declaresPrivate(name)) {
                return JsBoolean.of(object.getKlass() != null && object.getKlass().isSubclassOf(cls));
            }
        }
        return JsBoolean.FALSE;
    }

    public JsValue evalInstanceof(JsValue left, JsValue right) {
        return switch (right) {
            case JsClass cls -> JsBoolean.of(left instanceof JsObject object && object.getKlass() != null
                    && object.getKlass().isSubclassOf(cls));
            case JsFunction function -> JsBoolean.of(hasInPrototypeChain(left, function.getPrototype()));
            case JsNativeFunction nativeFunction when nativeFunction.isBound() ->
                evalInstanceof(left, nativeFunction.getBoundTarget());
            case JsNativeFunction ignored -> JsBoolean.FALSE;
            default -> throw new TypeErrorException("Right-hand side of 'instanceof' is not callable");
        };
    }
}

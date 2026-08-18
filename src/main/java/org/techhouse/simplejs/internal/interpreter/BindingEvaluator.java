package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.LEXICAL_KINDS;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.USING_KINDS;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.collectBoundNames;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.staticKeyName;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.internal.Completion;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.CatchClause;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// Binding and destructuring: variable/using declarations, for-target and catch-parameter binding,
// function parameter binding, and the single recursive destructuring routine (array/object
// patterns, defaults, rest, computed keys) parameterised by a per-context LeafBinder. Value
// evaluation and member access route through the Interpreter and MemberEvaluator seams.
public final class BindingEvaluator {
    private interface LeafBinder {
        void bind(JsNode leaf, JsValue value, Environment env);

        default PreparedTarget prepare(JsNode leaf, Environment env) {
            return value -> bind(leaf, value, env);
        }
    }

    @FunctionalInterface
    private interface PreparedTarget {
        void put(JsValue value);
    }

    // One element of an array pattern: the iterator record's [[Done]] flag lives here so a `next`
    // that throws marks the iteration done (no close) while an abrupt binding closes it.
    private static final class ArrayIteration {
        private final Iteration iteration;
        private boolean done;

        private ArrayIteration(Iteration iteration) {
            this.iteration = iteration;
        }

        private JsValue step() {
            if (done) {
                return null;
            }
            final JsValue item;
            try {
                item = iteration.next();
            } catch (RuntimeException error) {
                done = true;
                throw error;
            }
            done = item == null;
            return item;
        }

        private void close() {
            if (!done) {
                done = true;
                iteration.close();
            }
        }

        private void closeAfterThrow() {
            if (!done) {
                done = true;
                iteration.closeAfterThrow();
            }
        }
    }

    private final Interpreter interp;
    private final MemberEvaluator members;

    public BindingEvaluator(Interpreter interp, MemberEvaluator members) {
        this.interp = interp;
        this.members = members;
    }

    public Completion evalVariableDeclaration(VariableDeclaration declaration, Environment env) {
        final var kind = declaration.getKind();
        if (USING_KINDS.contains(kind)) {
            return evalUsingDeclaration(declaration, env, "await using".equals(kind));
        }
        if (!LEXICAL_KINDS.contains(kind) && !"var".equals(kind)) {
            throw new UnsupportedNodeException("VariableDeclaration kind '" + kind + "'");
        }
        for (final var declarator : declaration.getDeclarations()) {
            final var id = declarator.getId();
            final var init = declarator.getInit();
            if (id instanceof Identifier identifier) {
                final var name = identifier.getName();
                final var value = init == null ? JsUndefined.getInstance() : interp.eval(init, env);
                if (init != null) {
                    InterpreterUtils.applyInferredName(init, value, name);
                }
                if (LEXICAL_KINDS.contains(kind)) {
                    env.initialize(name, value);
                } else if (init != null) {
                    env.assign(name, value);
                }
            } else {
                final var value = init == null ? JsUndefined.getInstance() : interp.eval(init, env);
                destructure(id, value, env, declarationLeaf(kind));
            }
        }
        return Completion.empty();
    }

    private Completion evalUsingDeclaration(VariableDeclaration declaration, Environment env, boolean async) {
        for (final var declarator : declaration.getDeclarations()) {
            final var name = ((Identifier) declarator.getId()).getName();
            final var value = interp.eval(declarator.getInit(), env);
            InterpreterUtils.applyInferredName(declarator.getInit(), value, name);
            env.initialize(name, value);
            registerUsingResource(env, value, async);
        }
        return Completion.empty();
    }

    private void registerUsingResource(Environment env, JsValue value, boolean async) {
        if (async) {
            final var coroutine = interp.currentCoroutine();
            if (coroutine == null || !coroutine.isAsync()) {
                throw new SyntaxErrorException("await using is only valid inside an async function");
            }
        }
        if (isNullish(value)) {
            // AddDisposableResource's null/undefined short-circuit is sync-dispose only; an
            // `await using` of a nullish value still occupies a disposal slot (method undefined)
            // so disposal performs the implied Await(undefined) - a real microtask tick - at scope
            // exit.
            if (async) {
                env.registerDisposable(value, JsUndefined.getInstance(), true);
            }
            return;
        }
        final var method = disposeMethod(value, async);
        if (!isCallable(method)) {
            throw new TypeErrorException(
                    "Object does not have a " + (async ? "Symbol.asyncDispose" : "Symbol.dispose") + " method");
        }
        env.registerDisposable(value, method, async);
    }

    private JsValue disposeMethod(JsValue value, boolean async) {
        if (async) {
            final var asyncMethod = interp.getMemberByKey(value, JsSymbol.ASYNC_DISPOSE);
            if (!isNullish(asyncMethod)) {
                return asyncMethod;
            }
        }
        return interp.getMemberByKey(value, JsSymbol.DISPOSE);
    }

    public void bindForTarget(JsNode left, JsValue value, Environment env) {
        if (left instanceof VariableDeclaration declaration) {
            final var kind = declaration.getKind();
            final var id = declaration.getDeclarations().getFirst().getId();
            if (USING_KINDS.contains(kind)) {
                final var name = ((Identifier) id).getName();
                env.declareLexical(name, "const");
                env.initialize(name, value);
                registerUsingResource(env, value, "await using".equals(kind));
                return;
            }
            final var names = new ArrayList<String>();
            collectBoundNames(id, names);
            for (final var name : names) {
                if (LEXICAL_KINDS.contains(kind)) {
                    env.declareLexical(name, kind);
                } else {
                    env.declareVar(name);
                }
            }
            destructure(id, value, env, declarationLeaf(kind));
        } else {
            destructure(left, value, env, assignmentLeaf());
        }
    }

    public Completion evalCatch(CatchClause handler, JsValue error, Environment env) {
        final var catchEnv = env.child();
        final var param = handler.getParam();
        if (param instanceof Identifier id) {
            catchEnv.declareLexical(id.getName(), "let");
            catchEnv.initialize(id.getName(), error);
        } else if (param != null) {
            final var names = new ArrayList<String>();
            collectBoundNames(param, names);
            for (final var name : names) {
                catchEnv.declareLexical(name, "let");
            }
            destructure(param, error, catchEnv, declarationLeaf("let"));
        }
        return interp.evalBlock(handler.getBody(), catchEnv);
    }

    public void bindParams(List<JsNode> params, List<JsValue> args, Environment activation) {
        for (final var param : params) {
            declareParamNames(param, activation);
        }
        for (var i = 0; i < params.size(); i++) {
            final var param = params.get(i);
            if (param instanceof RestElement rest) {
                final var restArray = new JsArray();
                for (var j = i; j < args.size(); j++) {
                    restArray.push(args.get(j));
                }
                destructure(rest.getArgument(), restArray, activation, paramLeaf());
                return;
            }
            final var value = i < args.size() ? args.get(i) : JsUndefined.getInstance();
            if (param instanceof Identifier id) {
                activation.assign(id.getName(), value);
            } else {
                destructure(param, value, activation, paramLeaf());
            }
        }
    }

    private void declareParamNames(JsNode param, Environment activation) {
        final var names = new ArrayList<String>();
        collectBoundNames(param, names);
        for (final var name : names) {
            activation.declareParam(name);
        }
    }

    public void destructureAssignment(JsNode target, JsValue value, Environment env) {
        destructure(target, value, env, assignmentLeaf());
    }

    private void destructure(JsNode target, JsValue value, Environment env, LeafBinder leaf) {
        switch (target) {
            case AssignmentPattern pattern -> destructure(pattern.getLeft(), defaulted(pattern, value, env), env, leaf);
            case ArrayPattern pattern -> destructureArray(pattern, value, env, leaf);
            case ObjectPattern pattern -> destructureObject(pattern, value, env, leaf);
            default -> leaf.bind(target, value, env);
        }
    }

    private JsValue defaulted(AssignmentPattern pattern, JsValue value, Environment env) {
        if (!(value instanceof JsUndefined)) {
            return value;
        }
        final var resolved = interp.eval(pattern.getRight(), env);
        if (pattern.getLeft() instanceof Identifier id) {
            InterpreterUtils.applyInferredName(pattern.getRight(), resolved, id.getName());
        }
        return resolved;
    }

    private void destructureArray(ArrayPattern pattern, JsValue value, Environment env, LeafBinder leaf) {
        final var source = new ArrayIteration(new Iteration(interp, value));
        try {
            bindArrayElements(pattern, source, env, leaf);
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (Coroutine.ReturnSignal signal) {
            source.close();
            throw signal;
        } catch (RuntimeException error) {
            source.closeAfterThrow();
            throw error;
        }
        source.close();
    }

    private void bindArrayElements(ArrayPattern pattern, ArrayIteration source, Environment env, LeafBinder leaf) {
        for (final var element : pattern.getElements()) {
            if (element instanceof RestElement rest) {
                final var target = prepareTarget(rest.getArgument(), env, leaf);
                final var restArray = new JsArray();
                for (var item = source.step(); item != null; item = source.step()) {
                    restArray.push(item);
                }
                target.put(restArray);
                return;
            }
            final var target = element == null ? null : prepareTarget(element, env, leaf);
            final var item = source.step();
            if (target != null) {
                target.put(item == null ? JsUndefined.getInstance() : item);
            }
        }
    }

    // The assignment target of an element is a reference evaluated before the iterator is stepped,
    // so a throwing member expression aborts the destructuring before any `next` call.
    private PreparedTarget prepareTarget(JsNode element, Environment env, LeafBinder leaf) {
        if (element instanceof Identifier || element instanceof MemberExpression) {
            return leaf.prepare(element, env);
        }
        if (element instanceof AssignmentPattern pattern
                && (pattern.getLeft() instanceof Identifier || pattern.getLeft() instanceof MemberExpression)) {
            final var target = leaf.prepare(pattern.getLeft(), env);
            return value -> target.put(defaulted(pattern, value, env));
        }
        return value -> destructure(element, value, env, leaf);
    }

    private void destructureObject(ObjectPattern pattern, JsValue value, Environment env, LeafBinder leaf) {
        if (isNullish(value)) {
            throw new TypeErrorException(
                    "Cannot destructure '" + JsCoercion.toStr(value) + "' as it is " + JsCoercion.toStr(value) + ".");
        }
        final var taken = new ArrayList<JsValue>();
        for (final var member : pattern.getProperties()) {
            if (member instanceof RestElement rest) {
                final var restObject = new JsObject();
                restObject.setProto(interp.intrinsics().objectProto());
                copyDataProperties(restObject, value, taken);
                destructure(rest.getArgument(), restObject, env, leaf);
                return;
            }
            final var property = (Property) member;
            final var keyValue = property.isComputed()
                    ? JsCoercion.toPropertyKey(interp.eval(property.getKey(), env), interp.ops())
                    : new JsString(staticKeyName(property.getKey()));
            taken.add(keyValue);
            // KeyedDestructuringAssignmentEvaluation resolves the target reference (lref) before
            // reading the source property's value: a leaf target (identifier/member expression) must
            // be prepared first, so a getter on the source object is never observed to run before the
            // target's own base/key expressions (e.g. `this` in a derived constructor's TDZ).
            final var target = prepareTarget(property.getValue(), env, leaf);
            target.put(interp.getMemberByKey(value, keyValue));
        }
    }

    // CopyDataProperties(target, source, excludedNames): walks the source's real
    // [[OwnPropertyKeys]]/[[GetOwnProperty]]/[[Get]] through the ops seam rather than enumerating
    // its stored keys directly, so a Proxy source's trap order/values (including a getOwnKeys-only
    // trap that never reaches "get" for an excluded or non-enumerable key) and a symbol-keyed
    // accessor's getter are observed exactly like a conformant engine - the same shape as
    // Object.assign's source walk.
    private void copyDataProperties(JsObject target, JsValue source, List<JsValue> excludedNames) {
        final var ops = interp.ops();
        final var from = interp.intrinsics().toObject(source);
        for (final var key : ops.ownKeys(from)) {
            if (isExcludedKey(excludedNames, key)) {
                continue;
            }
            if (!(ops.getOwnPropertyDescriptor(from, key) instanceof JsObject descriptor)
                    || !JsCoercion.toBoolean(descriptor.get("enumerable"))) {
                continue;
            }
            final var propValue = ops.getMember(from, key);
            if (key instanceof JsSymbol symbol) {
                target.setSymbol(symbol, propValue);
            } else {
                target.set(((JsString) key).getValue(), propValue);
            }
        }
    }

    private boolean isExcludedKey(List<JsValue> excludedNames, JsValue key) {
        for (final var excluded : excludedNames) {
            if (sameKey(excluded, key)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameKey(JsValue a, JsValue b) {
        if (a instanceof JsString sa && b instanceof JsString sb) {
            return sa.getValue().equals(sb.getValue());
        }
        return a == b;
    }

    private LeafBinder declarationLeaf(String kind) {
        if (LEXICAL_KINDS.contains(kind)) {
            return (leaf, value, env) -> env.initialize(((Identifier) leaf).getName(), value);
        }
        return (leaf, value, env) -> env.assign(((Identifier) leaf).getName(), value);
    }

    private LeafBinder paramLeaf() {
        return (leaf, value, env) -> env.assign(((Identifier) leaf).getName(), value);
    }

    private LeafBinder assignmentLeaf() {
        return new LeafBinder() {
            @Override
            public void bind(JsNode leaf, JsValue value, Environment env) {
                if (leaf instanceof Identifier id) {
                    env.assign(id.getName(), value);
                } else if (leaf instanceof MemberExpression member) {
                    final var object = interp.eval(member.getObject(), env);
                    if (member.getProperty() instanceof PrivateIdentifier priv) {
                        interp.setPrivateMember(object, priv.getName(), value, env);
                    } else {
                        members.setMember(object, interp.memberKey(member, env), value);
                    }
                } else {
                    throw new UnsupportedNodeException(leaf.getType().name());
                }
            }

            @Override
            public PreparedTarget prepare(JsNode leaf, Environment env) {
                if (leaf instanceof MemberExpression member) {
                    final var target = interp.eval(member.getObject(), env);
                    if (member.getProperty() instanceof PrivateIdentifier priv) {
                        return value -> interp.setPrivateMember(target, priv.getName(), value, env);
                    }
                    // The reference keeps the raw key: ToPropertyKey is part of PutValue, so it must
                    // run when the value is finally put (after the source value has been read), not
                    // when the reference itself is created.
                    final var rawKey = interp.memberKeyValue(member, env);
                    return value -> members.setMember(target, JsCoercion.toStr(rawKey, interp.ops()), value);
                }
                return value -> bind(leaf, value, env);
            }
        };
    }
}

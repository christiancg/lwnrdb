package org.techhouse.simplejs.internal.interpreter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.techhouse.simplejs.internal.Completion;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsValue;

final class ForInEnumeration {
    private final Interpreter interp;
    private final ProxyDispatch proxies;
    private final StatementEvaluator statementEvaluator;

    ForInEnumeration(Interpreter interp, ProxyDispatch proxies, StatementEvaluator statementEvaluator) {
        this.interp = interp;
        this.proxies = proxies;
        this.statementEvaluator = statementEvaluator;
    }

    Completion evalForIn(ForInStatement statement, Environment env, String label) {
        final var target = statementEvaluator.loops.evalIterationSource(statement.getLeft(), statement.getRight(), env);
        final var targetOwnKeys = target instanceof JsObject object ? object.keys() : null;
        for (final var key : enumerateKeys(target)) {
            interp.tick();
            if (target instanceof JsObject object && targetOwnKeys.contains(key) && !object.has(key)
                    && !object.hasAccessor(key)) {
                continue;
            }
            final var iterationEnv = env.child();
            interp.bindForTarget(statement.getLeft(), new JsString(key), iterationEnv);
            final var completion = statementEvaluator.loops.evalIterationBody(statement.getBody(), iterationEnv);
            final var action = LoopAction.of(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
        }
        return Completion.empty();
    }

    boolean isEnumerableProxyKey(JsProxy proxy, JsValue key) {
        final var descriptor = proxies.getOwnPropertyDescriptor(proxy, key);
        return descriptor instanceof JsObject desc && desc.has("enumerable")
                && JsCoercion.toBoolean(desc.get("enumerable"));
    }

    List<String> enumerateKeys(JsValue target) {
        final var result = new ArrayList<String>();
        final var seen = new HashSet<String>();
        var synthesised = false;
        for (var current = target; current != null;) {
            for (final var key : ownStringKeys(current)) {
                if (seen.add(key) && isEnumerableOwn(current, key)) {
                    result.add(key);
                }
            }
            if (current instanceof JsProxy || current instanceof JsGlobalObject) {
                break;
            }
            final var next = current.getProto();
            if (next == null && !synthesised && !(current instanceof JsObject)) {
                current = interp.intrinsics().protoFor(current);
                synthesised = true;
            } else {
                current = next;
            }
        }
        return result;
    }

    List<String> ownStringKeys(JsValue target) {
        if (target instanceof JsGlobalObject global) {
            return new ArrayList<>(global.getEnv().allGlobalNames());
        }
        if (target instanceof JsProxy proxy) {
            final var keys = new ArrayList<String>();
            for (final var key : proxies.ownKeys(proxy)) {
                if (key instanceof JsString string) {
                    keys.add(string.getValue());
                }
            }
            return keys;
        }
        if (target instanceof JsClass cls) {
            return new ArrayList<>(cls.getStaticOwner().keys());
        }
        if (target instanceof JsObject object) {
            return new ArrayList<>(object.keys());
        }
        if (target instanceof JsArray array) {
            final var keys = new ArrayList<String>();
            for (var i = 0; i < array.length(); i++) {
                if (!array.isHole(i)) {
                    keys.add(Integer.toString(i));
                }
            }
            keys.addAll(array.namedPropertyKeys());
            return keys;
        }
        if (target instanceof JsString string) {
            final var keys = new ArrayList<String>();
            for (var i = 0; i < string.getValue().length(); i++) {
                keys.add(Integer.toString(i));
            }
            return keys;
        }
        if (target instanceof JsTypedArray typed) {
            final var keys = new ArrayList<String>();
            for (var i = 0; i < typed.length(); i++) {
                keys.add(Integer.toString(i));
            }
            final var table = typed.ownProperties();
            if (table != null) {
                keys.addAll(table.keys());
            }
            return keys;
        }
        if (target instanceof JsArguments arguments) {
            return arguments.enumerablePropertyKeys();
        }
        if (target instanceof JsCallableProperties callable) {
            return callable.enumerablePropertyKeys();
        }
        return List.of();
    }

    boolean isEnumerableOwn(JsValue target, String key) {
        return switch (target) {
            case JsGlobalObject global -> global.getEnv().enumerableGlobalNames().contains(key);
            case JsProxy proxy -> isEnumerableProxyKey(proxy, new JsString(key));
            case JsClass cls -> cls.getStaticOwner().isEnumerable(key);
            case JsObject object -> object.isEnumerable(key);
            case JsArray array -> arrayKeyEnumerable(array, key);
            case JsTypedArray typed -> typedArrayKeyEnumerable(typed, key);
            default -> true;
        };
    }

    static boolean arrayKeyEnumerable(JsArray array, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        return index == null ? array.getPropFlags(key).enumerable() : array.getIndexFlags(index).enumerable();
    }

    static boolean typedArrayKeyEnumerable(JsTypedArray typed, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        if (index != null && index < typed.length()) {
            return true;
        }
        final var table = typed.ownProperties();
        return table != null && table.isEnumerable(key);
    }
}

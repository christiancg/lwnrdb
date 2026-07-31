package org.techhouse.simplejs.internal.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.builtins.ErrorBuiltins;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.TypedArrayBuiltins;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// State-free helpers lifted out of the tree-walking Interpreter: small predicates, coercions,
// key/name derivations, binding-name collection, array/object shape helpers and error mapping.
// These depend only on their arguments plus static builtins, so they carry none of the
// interpreter's execution state and are pulled back in via a static import.
public final class InterpreterUtils {
    public static final Set<String> LOGICAL_ASSIGN = Set.of("&&=", "||=", "??=");
    public static final Set<String> LEXICAL_KINDS = Set.of("let", "const");
    public static final Set<String> USING_KINDS = Set.of("using", "await using");

    private InterpreterUtils() {
    }

    public static boolean isNullish(JsValue value) {
        return value instanceof JsNull || value instanceof JsUndefined;
    }

    public static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    public static boolean isObjectLike(JsValue value) {
        return value instanceof JsObject || value instanceof JsArray || value instanceof JsFunction
                || value instanceof JsNativeFunction || value instanceof JsClass;
    }

    public static Integer arrayIndex(String key) {
        if (key.isEmpty()) {
            return null;
        }
        for (var i = 0; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return null;
            }
        }
        if (key.length() > 1 && key.charAt(0) == '0') {
            return null;
        }
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static JsValue numericOld(JsValue oldValue, InterpreterOps ops) {
        if (oldValue instanceof JsBigInt) {
            return oldValue;
        }
        return new JsNumber(JsCoercion.toNumber(oldValue, ops));
    }

    public static String baseOperator(String assignmentOperator) {
        return assignmentOperator.substring(0, assignmentOperator.length() - 1);
    }

    public static boolean shouldNotApplyLogical(String operator, JsValue current) {
        return !switch (operator) {
            case "&&=" -> JsCoercion.toBoolean(current);
            case "||=" -> !JsCoercion.toBoolean(current);
            case "??=" -> isNullish(current);
            default -> throw new TypeErrorException("Unknown logical assignment: " + operator);
        };
    }

    public static String labelName(Identifier label) {
        return label == null ? null : label.getName();
    }

    public static boolean matchesLabel(String completionLabel, String loopLabel) {
        return completionLabel == null || completionLabel.equals(loopLabel);
    }

    public static JsValue arg0(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }

    public static JsValue arg1(List<JsValue> args) {
        return args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
    }

    public static JsValue orUndefined(JsValue value) {
        return value == null ? JsUndefined.getInstance() : value;
    }

    public static String staticKeyName(Expression key) {
        return switch (key.getType()) {
            case IDENTIFIER -> ((Identifier) key).getName();
            case STRING_LITERAL -> ((StringLiteral) key).getValue();
            case NUMBER_LITERAL -> JsCoercion.toStr(new JsNumber(((NumberLiteral) key).getValue().doubleValue()));
            default -> throw new UnsupportedNodeException(key.getType().name());
        };
    }

    public static void spreadObject(JsObject target, JsValue source) {
        switch (source) {
            case JsObject object -> {
                for (final var entry : object.getProperties().entrySet()) {
                    if (object.isEnumerable(entry.getKey())) {
                        target.set(entry.getKey(), entry.getValue());
                    }
                }
            }
            case JsArray array -> {
                final var elements = array.getElements();
                for (var i = 0; i < elements.size(); i++) {
                    target.set(Integer.toString(i), elements.get(i));
                }
            }
            case JsString string -> {
                for (var i = 0; i < string.getValue().length(); i++) {
                    target.set(Integer.toString(i), new JsString(String.valueOf(string.getValue().charAt(i))));
                }
            }
            default -> {
            }
        }
    }

    public static List<JsValue> arrayLikeElements(JsValue value) {
        if (value instanceof JsArray array) {
            return array.getElements();
        }
        if (value instanceof JsArguments arguments) {
            return arguments.snapshot();
        }
        if (value instanceof JsTypedArray typed) {
            return TypedArrayBuiltins.elements(typed);
        }
        if (value instanceof JsString string) {
            final var chars = new ArrayList<JsValue>();
            for (var i = 0; i < string.getValue().length(); i++) {
                chars.add(new JsString(String.valueOf(string.getValue().charAt(i))));
            }
            return chars;
        }
        throw new TypeErrorException(JsCoercion.toStr(value) + " is not iterable");
    }

    public static void collectBoundNames(JsNode target, List<String> names) {
        switch (target) {
            case Identifier id -> names.add(id.getName());
            case AssignmentPattern pattern -> collectBoundNames(pattern.getLeft(), names);
            case RestElement rest -> collectBoundNames(rest.getArgument(), names);
            case ArrayPattern pattern -> {
                for (final var element : pattern.getElements()) {
                    if (element != null) {
                        collectBoundNames(element, names);
                    }
                }
            }
            case ObjectPattern pattern -> {
                for (final var member : pattern.getProperties()) {
                    if (member instanceof RestElement rest) {
                        collectBoundNames(rest.getArgument(), names);
                    } else {
                        collectBoundNames(((Property) member).getValue(), names);
                    }
                }
            }
            default -> {
            }
        }
    }

    public static List<JsValue> objectOwnKeys(JsObject object) {
        final var keys = new ArrayList<JsValue>();
        for (final var key : object.keys()) {
            keys.add(new JsString(key));
        }
        return keys;
    }

    public static List<JsValue> arrayOwnKeys(JsArray array) {
        final var keys = new ArrayList<JsValue>();
        for (var i = 0; i < array.length(); i++) {
            keys.add(new JsString(Integer.toString(i)));
        }
        keys.add(new JsString("length"));
        return keys;
    }

    public static boolean arrayHasMember(JsArray array, String key) {
        if ("length".equals(key)) {
            return true;
        }
        final var index = arrayIndex(key);
        return index != null && index < array.length();
    }

    public static boolean deleteArrayElement(JsArray array, String key) {
        final var index = arrayIndex(key);
        if (index != null && index < array.length()) {
            array.set(index, JsUndefined.getInstance());
        }
        return true;
    }

    public static boolean hasInPrototypeChain(JsValue left, JsObject prototype) {
        if (left instanceof JsObject object) {
            for (var proto = object.getProto(); proto != null; proto = proto.getProto()) {
                if (proto == prototype) {
                    return true;
                }
            }
        }
        return false;
    }

    public static TypeErrorException cannotReadProperties(JsValue target, String key) {
        return new TypeErrorException(
                "Cannot read properties of " + JsCoercion.toStr(target) + " (reading '" + key + "')");
    }

    public static JsValue stepResult(Coroutine.StepResult step) {
        return stepResult(step.value(), step.done());
    }

    public static JsValue stepResult(JsValue value, boolean done) {
        final var result = new JsObject();
        result.set("value", value);
        result.set("done", JsBoolean.of(done));
        return result;
    }

    public static JsValue toErrorValue(RuntimeException error) {
        if (error instanceof JsThrowException thrown) {
            return thrown.getValue();
        }
        final var name = switch (error) {
            case TypeErrorException ignored -> "TypeError";
            case ReferenceErrorException ignored -> "ReferenceError";
            case RangeErrorException ignored -> "RangeError";
            default -> "SyntaxError";
        };
        return ErrorBuiltins.makeError(name, error.getMessage());
    }
}

package org.techhouse.simplejs.internal.interpreter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.builtins.ErrorBuiltins;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.IterableToList;
import org.techhouse.simplejs.builtins.TypedArrayBuiltins;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.ClassExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.FunctionExpression;
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
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
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

    // IsAnonymousFunctionDefinition: only a function/class *expression* without its own binding
    // identifier takes the name of what it is assigned to. A parenthesized expression parses to the
    // inner node (so it still qualifies), while `(0, function(){})` parses to a SequenceExpression
    // and must not.
    public static boolean isAnonymousFunctionDefinition(JsNode node) {
        return switch (node) {
            case null -> false;
            case ArrowFunctionExpression ignored -> true;
            case FunctionExpression function -> function.getName() == null;
            case ClassExpression classExpression -> classExpression.getId() == null;
            default -> false;
        };
    }

    public static void setFunctionName(JsValue value, String name) {
        switch (value) {
            case JsFunction function -> function.setInferredName(name);
            case JsClass classValue -> classValue.setInferredName(name);
            default -> {
            }
        }
    }

    public static void applyInferredName(JsNode source, JsValue value, String name) {
        if (name != null && isAnonymousFunctionDefinition(source)) {
            setFunctionName(value, name);
        }
    }

    // The single source of truth for [[Construct]]: Interpreter.constructValue guards on it, so a
    // value that answers false here is never reachable via `new`.
    public static boolean isConstructor(JsValue value) {
        return switch (value) {
            case JsProxy proxy -> proxy.isConstructor();
            case JsClass ignored -> true;
            case JsNativeFunction nativeFunction -> nativeFunction.isConstructor();
            case JsFunction function -> function.isConstructor();
            default -> false;
        };
    }

    // A deny-list, not an allow-list: every non-primitive JsValue subtype is an object to the spec,
    // so a value type added later must not silently regress iteration or construction.
    public static boolean isObjectLike(JsValue value) {
        return !(value instanceof JsUndefined || value instanceof JsNull || value instanceof JsBoolean
                || value instanceof JsNumber || value instanceof JsString || value instanceof JsBigInt
                || value instanceof JsSymbol);
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

    // Spec CanonicalNumericIndexString: a key that round-trips through Number->String is an
    // "integer-indexed" access on a typed array even when it isn't a valid array index (e.g.
    // "1.1", "-1", "-0", "NaN") - such keys must resolve via the exotic [[Get]]/[[Set]] (returning
    // undefined / no-op) and never fall through to the prototype chain, unlike an ordinary object.
    public static boolean isCanonicalNumericIndexString(String key) {
        if ("-0".equals(key)) {
            return true;
        }
        final double parsed;
        try {
            parsed = Double.parseDouble(key);
        } catch (NumberFormatException ignored) {
            return false;
        }
        return org.techhouse.ejson.internal.NumberFormatter.toJsString(parsed).equals(key);
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

    // An accessor's value is only reachable by invoking its getter, which JsObject cannot do by design.
    public static JsValue ownValue(JsObject object, String key, InterpreterOps ops) {
        if (ops != null && object.hasAccessor(key)) {
            return ops.getMember(object, new JsString(key));
        }
        return object.get(key);
    }

    public static void spreadObject(JsObject target, JsValue source, InterpreterOps ops) {
        switch (source) {
            case JsObject object -> {
                for (final var key : object.keys()) {
                    if (object.isEnumerable(key)) {
                        target.set(key, ownValue(object, key, ops));
                    }
                }
                for (final var symbol : object.symbolKeys()) {
                    target.setSymbol(symbol, object.getSymbol(symbol));
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

    // A generic array-like is snapshotted by reading `length` then every index through the member
    // seam, so getters and inherited index properties are honoured; a missing index is a hole. A
    // method that iterates backwards reads the indices in descending order (`fromEnd`) so a throwing
    // getter is observed in the same order as the spec's lazy walk.
    public static List<JsValue> arrayLikeElements(JsValue value, InterpreterOps ops, boolean fromEnd) {
        if (ops == null || !(value instanceof JsObject || value instanceof JsProxy)) {
            return arrayLikeElements(value);
        }
        final var length = toLength(ops.getMember(value, new JsString("length")), ops);
        final var elements = new ArrayList<JsValue>(length);
        for (var i = 0; i < length; i++) {
            final var index = fromEnd ? length - 1 - i : i;
            final var key = new JsString(Integer.toString(index));
            final var element = ops.getMember(value, key);
            elements.add(element instanceof JsUndefined && !ops.has(value, key) ? JsUndefined.getHole() : element);
        }
        if (fromEnd) {
            Collections.reverse(elements);
        }
        return elements;
    }

    // Array.from and the %TypedArray% constructor/from check for a callable @@iterator first and
    // only fall back to array-like (length + indexed Get) semantics when it is absent — unlike a
    // plain iterableToList.drain, which requires a true iterable. A throwing @@iterator getter still
    // propagates here, since the lookup itself runs through the member seam. A generator or an
    // arguments object is iterated structurally by Iteration (not via a real @@iterator member), so
    // both are treated as having a callable iterator without consulting the member seam.
    public static List<JsValue> arrayLikeOrIterableToList(JsValue source, IterableToList iterableToList,
            InterpreterOps ops) {
        if (source instanceof JsGenerator || source instanceof JsArguments
                || isCallable(ops.getMember(source, JsSymbol.ITERATOR))) {
            return iterableToList.drain(source);
        }
        return arrayLikeElements(source, ops, false);
    }

    // A length past the int range cannot be materialised by the snapshot model, and every spec path
    // that would need one throws anyway ("integer limit exceeded"), so report that rather than hang.
    private static int toLength(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || number <= 0) {
            return 0;
        }
        if (number > Integer.MAX_VALUE) {
            throw new TypeErrorException("Array-like receiver length exceeds the supported maximum");
        }
        return (int) number;
    }

    public static List<JsValue> stringCodePoints(String value) {
        final var points = new ArrayList<JsValue>();
        var i = 0;
        while (i < value.length()) {
            final var point = value.codePointAt(i);
            final var width = Character.charCount(point);
            points.add(new JsString(value.substring(i, i + width)));
            i += width;
        }
        return points;
    }

    // The iterator protocol walks a string by code point, while its indexed properties (and every
    // generic array-like path built on them) stay code units — arrayLikeElements is deliberately unchanged.
    public static List<JsValue> iterableElements(JsValue value) {
        if (value instanceof JsString string) {
            return stringCodePoints(string.getValue());
        }
        return arrayLikeElements(value);
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
        return index != null && index < array.length() && !array.isHole(index);
    }

    public static boolean deleteArrayElement(JsArray array, String key) {
        final var index = arrayIndex(key);
        if (index != null) {
            if (index >= array.length() || array.isHole(index)) {
                return true;
            }
            if (!array.getIndexFlags(index).configurable()) {
                return false;
            }
            array.clearIndexToHole(index);
            return true;
        }
        if (!array.hasProperty(key) && !array.hasPropAccessor(key)) {
            return true;
        }
        if (!array.getPropFlags(key).configurable()) {
            return false;
        }
        return array.deleteProperty(key);
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
        if (step.value() instanceof YieldDelegation.PassThrough passThrough) {
            return passThrough.result();
        }
        return stepResult(step.value(), step.done());
    }

    public static JsValue stepResult(JsValue value, boolean done) {
        final var result = new JsObject();
        result.set("value", value);
        result.set("done", JsBoolean.of(done));
        return result;
    }

    public static JsValue toErrorValue(RuntimeException error) {
        return toErrorValue(error, null);
    }

    public static JsValue toErrorValue(RuntimeException error, Intrinsics intrinsics) {
        if (error instanceof JsThrowException thrown) {
            return thrown.getValue();
        }
        final var name = switch (error) {
            case TypeErrorException ignored -> "TypeError";
            case ReferenceErrorException ignored -> "ReferenceError";
            case RangeErrorException ignored -> "RangeError";
            default -> "SyntaxError";
        };
        return intrinsics == null
                ? ErrorBuiltins.makeError(name, error.getMessage())
                : intrinsics.makeError(name, error.getMessage());
    }
}

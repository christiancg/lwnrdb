package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.regex.RegexEscape.escape;
import static org.techhouse.simplejs.builtins.regex.RegexExec.builtinExec;
import static org.techhouse.simplejs.builtins.regex.RegexExec.regExpExec;
import static org.techhouse.simplejs.internal.regex.RegexFlags.ACCESSOR_NAMES;
import static org.techhouse.simplejs.internal.regex.RegexFlags.FLAG_ACCESSOR_NAMES;
import static org.techhouse.simplejs.internal.regex.RegexFlags.NON_FLAGS_ACCESSOR_NAMES;
import static org.techhouse.simplejs.internal.regex.RegexFlags.VALID_FLAGS;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.SameValueZero;

public final class RegexBuiltins {
    public static final List<String> NAMES = List.of("test", "exec", "toString");
    public static final List<String> PROTO_ACCESSORS = ACCESSOR_NAMES;
    public static final JsSymbol ITERATOR_STATE = new JsSymbol("RegExpStringIterator state");

    public static final String OTHER_PUNCTUATORS = ",-=<>#&!%:;@~'`\"";
    public static final char VERTICAL_TAB = '\u000B';
    public static final char LINE_SEPARATOR = '\u2028';
    public static final char PARAGRAPH_SEPARATOR = '\u2029';
    public static final char NO_BREAK_SPACE = '\u00a0';
    public static final char BYTE_ORDER_MARK = '\ufeff';

    private RegexBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var self = new JsValue[1];
        final var regExp = new JsNativeFunction("RegExp", (_, args) -> construct(args, self[0], ops));
        self[0] = regExp;
        regExp.setProperty("escape", new JsNativeFunction("escape", (_, args) -> new JsString(escape(args))));
        final var speciesGetter = new JsNativeFunction("get [Symbol.species]", (thisArg, _) -> thisArg);
        speciesGetter.setLength(0);
        regExp.ownProperties().defineSymbolAccessor(JsSymbol.SPECIES, speciesGetter, null);
        regExp.ownProperties().setSymbolFlags(JsSymbol.SPECIES, JsObject.PropertyFlags.TAG);
        return regExp;
    }

    private static JsValue construct(List<JsValue> args, JsValue self, InterpreterOps ops) {
        final var first = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var flagsArg = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        final var explicitFlags = !(flagsArg instanceof JsUndefined);
        if (!explicitFlags && JsNativeFunction.currentNewTarget() == null && isRegExp(first, ops)
                && SameValueZero.equal(ops.getMember(first, new JsString("constructor")), self)) {
            return first;
        }
        if (first instanceof JsRegExp existing) {
            return RegexTranslator.compile(existing.getSource(),
                    explicitFlags ? JsCoercion.toStr(flagsArg, ops) : existing.getFlags());
        }
        if (isRegExp(first, ops)) {
            final var source = JsCoercion.toStr(ops.getMember(first, new JsString("source")), ops);
            final var flags = explicitFlags
                    ? JsCoercion.toStr(flagsArg, ops)
                    : JsCoercion.toStr(ops.getMember(first, new JsString("flags")), ops);
            return RegexTranslator.compile(source, flags);
        }
        final var source = first instanceof JsUndefined ? "" : JsCoercion.toStr(first, ops);
        return RegexTranslator.compile(source, explicitFlags ? JsCoercion.toStr(flagsArg, ops) : "");
    }

    public static boolean isRegExp(JsValue value, InterpreterOps ops) {
        if (ops == null || !InterpreterUtils.isObjectLike(value)) {
            return false;
        }
        final var matcher = ops.getMember(value, JsSymbol.MATCH);
        return matcher instanceof JsUndefined ? value instanceof JsRegExp : JsCoercion.toBoolean(matcher);
    }

    public static boolean isAccessor(String name) {
        return NON_FLAGS_ACCESSOR_NAMES.contains(name);
    }

    public static boolean isSetterless(String name) {
        return ACCESSOR_NAMES.contains(name);
    }

    public static JsValue protoAccessor(JsValue receiver, String name, JsObject regexpProto, InterpreterOps ops) {
        if ("flags".equals(name)) {
            return new JsString(flagsOf(receiver, regexpProto, ops));
        }
        if (receiver instanceof JsRegExp regexp) {
            return getMethod(regexp, name);
        }
        if (receiver == regexpProto) {
            return "source".equals(name) ? new JsString("(?:)") : JsUndefined.getInstance();
        }
        throw new TypeErrorException("RegExp.prototype." + name + " called on an incompatible receiver");
    }

    private static String regExpToString(JsValue target, JsRegExp state, InterpreterOps ops) {
        if (ops == null) {
            return "/" + state.getSource() + "/" + canonicalFlags(state);
        }
        return "/" + JsCoercion.toStr(ops.getMember(target, new JsString("source")), ops) + "/"
                + JsCoercion.toStr(ops.getMember(target, new JsString("flags")), ops);
    }

    private static String canonicalFlags(JsRegExp regexp) {
        final var flags = new StringBuilder();
        for (var i = 0; i < VALID_FLAGS.length(); i++) {
            if (regexp.getFlags().indexOf(VALID_FLAGS.charAt(i)) >= 0) {
                flags.append(VALID_FLAGS.charAt(i));
            }
        }
        return flags.toString();
    }

    private static String flagsOf(JsValue receiver, JsObject regexpProto, InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(receiver)) {
            throw new TypeErrorException("RegExp.prototype.flags called on an incompatible receiver");
        }
        if (receiver == regexpProto) {
            return "";
        }
        final var flags = new StringBuilder();
        for (var i = 0; i < FLAG_ACCESSOR_NAMES.size(); i++) {
            if (JsCoercion.toBoolean(ops.getMember(receiver, new JsString(FLAG_ACCESSOR_NAMES.get(i))))) {
                flags.append(VALID_FLAGS.charAt(i));
            }
        }
        return flags.toString();
    }

    public static JsValue getMethod(JsRegExp receiver, String name) {
        return getMethod(receiver, name, null);
    }

    public static JsValue getMethod(JsRegExp receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "test" -> new JsNativeFunction("test", (thisArg, args) -> JsBoolean
                    .of(!(regExpExec(receiverOf(thisArg, receiver), str(args, ops), ops) instanceof JsNull)));
            case "exec" -> new JsNativeFunction("exec",
                    (thisArg, args) -> builtinExec(receiverOf(thisArg, receiver), receiver, str(args, ops), ops));
            case "toString" -> new JsNativeFunction("toString",
                    (thisArg, _) -> new JsString(regExpToString(receiverOf(thisArg, receiver), receiver, ops)));
            case "source" -> new JsString(receiver.getSource());
            case "flags" -> new JsString(canonicalFlags(receiver));
            case "global" -> JsBoolean.of(receiver.isGlobal());
            case "ignoreCase" -> JsBoolean.of(receiver.isIgnoreCase());
            case "multiline" -> JsBoolean.of(receiver.isMultiline());
            case "dotAll" -> JsBoolean.of(receiver.isDotAll());
            case "sticky" -> JsBoolean.of(receiver.isSticky());
            case "hasIndices" -> JsBoolean.of(receiver.hasIndices());
            case "unicode" -> JsBoolean.of(receiver.isUnicode());
            case "unicodeSets" -> JsBoolean.of(receiver.isUnicodeSets());
            default -> null;
        };
    }

    private static JsValue receiverOf(JsValue thisArg, JsRegExp fallback) {
        return InterpreterUtils.isObjectLike(thisArg) ? thisArg : fallback;
    }

    private static String str(List<JsValue> args, InterpreterOps ops) {
        return args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst(), ops);
    }
}

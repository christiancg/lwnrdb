package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.SameValueZero;

public final class RegexBuiltins {
    public static final List<String> NAMES = List.of("test", "exec", "toString");
    // lastIndex is deliberately absent: it is an own data property of each instance, not an accessor
    // inherited from RegExp.prototype.
    public static final List<String> PROTO_ACCESSORS = List.of("source", "flags", "global", "ignoreCase", "multiline",
            "dotAll", "sticky", "hasIndices", "unicode", "unicodeSets");
    // "flags" is deliberately absent: it must always run the generic accessor, which reads the eight
    // flag properties back off the receiver, so an overridden `global` getter is observed.
    private static final Set<String> ACCESSORS = Set.of("source", "global", "ignoreCase", "multiline", "dotAll",
            "sticky", "hasIndices", "unicode", "unicodeSets");
    private static final Set<String> UNWRITABLE = Set.of("source", "flags", "global", "ignoreCase", "multiline",
            "dotAll", "sticky", "hasIndices", "unicode", "unicodeSets");
    // RegExp.prototype.flags reads each flag back off the receiver, in this order.
    private static final List<String> FLAG_ACCESSORS = List.of("hasIndices", "global", "ignoreCase", "multiline",
            "dotAll", "unicode", "unicodeSets", "sticky");
    private static final String FLAG_CHARS = "dgimsuvy";
    private static final String LAST_INDEX = "lastIndex";
    private static final JsSymbol ITERATOR_STATE = new JsSymbol("RegExpStringIterator state");
    private static final double MAX_SAFE_LENGTH = 9007199254740991d;

    private static final String SYNTAX_CHARACTERS = "^$\\.*+?()[]{}|/";
    private static final String OTHER_PUNCTUATORS = ",-=<>#&!%:;@~'`\"";
    private static final char VERTICAL_TAB = '\u000B';
    private static final char LINE_SEPARATOR = '\u2028';
    private static final char PARAGRAPH_SEPARATOR = '\u2029';
    private static final char NO_BREAK_SPACE = '\u00a0';
    private static final char BYTE_ORDER_MARK = '\ufeff';

    private RegexBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var self = new JsValue[1];
        final var regExp = new JsNativeFunction("RegExp", (_, args) -> construct(args, self[0], ops));
        self[0] = regExp;
        regExp.setProperty("escape", new JsNativeFunction("escape", (_, args) -> new JsString(escape(args))));
        // %RegExp%[Symbol.species] returns the receiver unchanged - speciesConstructor's own
        // Get(constructor, Symbol.species) lookup already falls back to %RegExp% when this is
        // absent, so the accessor's only observable effect is making it discoverable via
        // getOwnPropertyDescriptor (test262 built-ins/Function/prototype/toString/
        // symbol-named-builtins.js asserts the getter itself is a function).
        final var speciesGetter = new JsNativeFunction("get [Symbol.species]", (thisArg, _) -> thisArg);
        speciesGetter.setLength(0);
        regExp.ownProperties().defineSymbolAccessor(JsSymbol.SPECIES, speciesGetter, null);
        regExp.ownProperties().setSymbolFlags(JsSymbol.SPECIES, new JsObject.PropertyFlags(false, false, true));
        return regExp;
    }

    private static String escape(List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsString first)) {
            throw new TypeErrorException("RegExp.escape argument must be a string");
        }
        final var value = first.getValue();
        final var result = new StringBuilder(value.length());
        for (var i = 0; i < value.length(); i++) {
            final var ch = value.charAt(i);
            if (i == 0 && isAlphanumeric(ch)) {
                appendHex(result, ch);
            } else if (SYNTAX_CHARACTERS.indexOf(ch) >= 0) {
                result.append('\\').append(ch);
            } else {
                appendNamedOrLiteral(result, value, i);
            }
        }
        return result.toString();
    }

    private static void appendNamedOrLiteral(StringBuilder result, String value, int index) {
        final var ch = value.charAt(index);
        switch (ch) {
            case '\t' -> result.append("\\t");
            case '\n' -> result.append("\\n");
            case VERTICAL_TAB -> result.append("\\v");
            case '\f' -> result.append("\\f");
            case '\r' -> result.append("\\r");
            default -> {
                if (OTHER_PUNCTUATORS.indexOf(ch) >= 0 || isWhiteSpace(ch) || isLineTerminator(ch)
                        || isLoneSurrogate(value, index)) {
                    appendHex(result, ch);
                } else {
                    result.append(ch);
                }
            }
        }
    }

    private static boolean isAlphanumeric(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    private static boolean isLineTerminator(char ch) {
        return ch == LINE_SEPARATOR || ch == PARAGRAPH_SEPARATOR;
    }

    // ECMA-262 WhiteSpace, which is not java's: NBSP, NNBSP and the byte order mark are all
    // WhiteSpace to the grammar but not to Character.isWhitespace.
    private static boolean isWhiteSpace(char ch) {
        return ch == ' ' || ch == NO_BREAK_SPACE || ch == BYTE_ORDER_MARK
                || Character.getType(ch) == Character.SPACE_SEPARATOR;
    }

    // A surrogate that is not part of a well-formed pair has no printable spelling, so it is escaped;
    // a pair is left alone and emitted as the two code units of its code point.
    private static boolean isLoneSurrogate(String value, int index) {
        final var ch = value.charAt(index);
        if (Character.isHighSurrogate(ch)) {
            return index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1));
        }
        return Character.isLowSurrogate(ch) && (index == 0 || !Character.isHighSurrogate(value.charAt(index - 1)));
    }

    private static void appendHex(StringBuilder result, char ch) {
        if (ch <= 0xFF) {
            result.append("\\x").append(String.format("%02x", (int) ch));
        } else {
            result.append("\\u").append(String.format("%04x", (int) ch));
        }
    }

    // Spec RegExp(pattern, flags): a RegExp argument contributes its source/flags directly, and any
    // other IsRegExp object contributes them through Get, so a regexp-like object works too.
    private static JsValue construct(List<JsValue> args, JsValue self, InterpreterOps ops) {
        final var first = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var flagsArg = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        final var explicitFlags = !(flagsArg instanceof JsUndefined);
        // Called (not constructed) with a regexp-like whose own constructor is this very function and
        // no flags of its own, the pattern is returned unchanged rather than re-compiled.
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

    // Spec IsRegExp(argument): Get(argument, @@match), ToBoolean it when not undefined, else fall
    // back to the [[RegExpMatcher]] brand.
    public static boolean isRegExp(JsValue value, InterpreterOps ops) {
        if (ops == null || !InterpreterUtils.isObjectLike(value)) {
            return false;
        }
        final var matcher = ops.getMember(value, JsSymbol.MATCH);
        return matcher instanceof JsUndefined ? value instanceof JsRegExp : JsCoercion.toBoolean(matcher);
    }

    public static boolean isAccessor(String name) {
        return ACCESSORS.contains(name);
    }

    // The inherited flag accessors have no setter, so an assignment to one is refused unless the
    // instance has shadowed it with an own property of its own.
    public static boolean isSetterless(String name) {
        return UNWRITABLE.contains(name);
    }

    // The prototype accessors are generic: a non-RegExp receiver is a TypeError, except
    // %RegExp.prototype% itself, which the spec makes report the "(?:)" / undefined placeholders so
    // that reading a flag off the bare prototype does not throw.
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

    // `flags` is derived, never the literal [[OriginalFlags]] text, so it always reports dgimsuvy order.
    private static String canonicalFlags(JsRegExp regexp) {
        final var flags = new StringBuilder();
        for (var i = 0; i < FLAG_CHARS.length(); i++) {
            if (regexp.getFlags().indexOf(FLAG_CHARS.charAt(i)) >= 0) {
                flags.append(FLAG_CHARS.charAt(i));
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
        for (var i = 0; i < FLAG_ACCESSORS.size(); i++) {
            if (JsCoercion.toBoolean(ops.getMember(receiver, new JsString(FLAG_ACCESSORS.get(i))))) {
                flags.append(FLAG_CHARS.charAt(i));
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

    // The receiver a `class extends RegExp` instance presents to exec/test is the outer object (whose
    // lastIndex and overridden `exec` are the observable ones); the closed-over JsRegExp only carries
    // the compiled matcher.
    private static JsValue receiverOf(JsValue thisArg, JsRegExp fallback) {
        return InterpreterUtils.isObjectLike(thisArg) ? thisArg : fallback;
    }

    // Spec RegExpBuiltinExec: `global`/`sticky` come from [[OriginalFlags]] but lastIndex is read and
    // written through the receiver's ordinary [[Get]]/[[Set]], so both can be observed and refused.
    private static JsValue builtinExec(JsValue target, JsRegExp state, String input, InterpreterOps ops) {
        final var global = state.isGlobal();
        final var sticky = state.isSticky();
        final var stateful = global || sticky;
        // lastIndex is read even when it is about to be discarded: the Get is observable.
        final var read = toLength(readLastIndex(target, ops), ops);
        final var lastIndex = stateful ? read : 0;
        if (lastIndex > input.length()) {
            resetLastIndex(target, stateful, ops);
            return JsNull.getInstance();
        }
        final var start = (int) lastIndex;
        final var matcher = state.getPattern().matcher(input);
        final var found = sticky ? matcher.find(start) && matcher.start() == start : matcher.find(start);
        if (!found) {
            resetLastIndex(target, stateful, ops);
            return JsNull.getInstance();
        }
        if (stateful) {
            writeLastIndex(target, matcher.end(), ops);
        }
        final var result = buildMatchResult(matcher, input, state);
        if (state.hasIndices()) {
            addIndices(result, matcher, state);
        }
        return result;
    }

    private static JsValue readLastIndex(JsValue target, InterpreterOps ops) {
        if (ops == null) {
            return target instanceof JsRegExp regexp ? regexp.getLastIndex() : new JsNumber(0);
        }
        return ops.getMember(target, new JsString(LAST_INDEX));
    }

    private static void resetLastIndex(JsValue target, boolean stateful, InterpreterOps ops) {
        if (stateful) {
            writeLastIndex(target, 0, ops);
        }
    }

    private static void writeLastIndex(JsValue target, int value, InterpreterOps ops) {
        if (ops == null) {
            if (target instanceof JsRegExp regexp) {
                regexp.setLastIndex(value);
            }
            return;
        }
        setOrThrow(target, new JsNumber(value), ops);
    }

    private static void setOrThrow(JsValue target, JsValue value, InterpreterOps ops) {
        if (!ops.setMember(target, new JsString(LAST_INDEX), value)) {
            throw new TypeErrorException("Cannot assign to read only property 'lastIndex'");
        }
    }

    private static double toLength(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || number <= 0) {
            return 0;
        }
        return Math.min(Math.floor(number), MAX_SAFE_LENGTH);
    }

    public static void addIndices(JsArray result, Matcher matcher, JsRegExp regexp) {
        final var indices = new JsArray();
        for (var i = 0; i <= matcher.groupCount(); i++) {
            indices.push(pair(matcher.start(i), matcher.end(i)));
        }
        final var names = groupNames(regexp);
        if (names.isEmpty()) {
            indices.setProperty("groups", JsUndefined.getInstance());
        } else {
            final var groups = new JsObject();
            for (final var groupName : names) {
                final var alias = participatingGroup(regexp, groupName, matcher);
                groups.set(groupName,
                        alias == null ? JsUndefined.getInstance() : pair(matcher.start(alias), matcher.end(alias)));
            }
            indices.setProperty("groups", groups);
        }
        result.setProperty("indices", indices);
    }

    private static JsValue pair(int start, int end) {
        if (start < 0) {
            return JsUndefined.getInstance();
        }
        return new JsArray(List.of(new JsNumber(start), new JsNumber(end)));
    }

    public static JsArray buildMatchResult(Matcher matcher, String input, JsRegExp regexp) {
        final var result = new JsArray();
        final var count = matcher.groupCount();
        for (var i = 0; i <= count; i++) {
            result.push(groupValue(matcher.group(i)));
        }
        result.setProperty("index", new JsNumber(matcher.start()));
        result.setProperty("input", new JsString(input));
        final var names = groupNames(regexp);
        if (names.isEmpty()) {
            result.setProperty("groups", JsUndefined.getInstance());
        } else {
            final var groups = new JsObject();
            for (final var groupName : names) {
                final var alias = participatingGroup(regexp, groupName, matcher);
                groups.set(groupName, alias == null ? JsUndefined.getInstance() : groupValue(matcher.group(alias)));
            }
            result.setProperty("groups", groups);
        }
        return result;
    }

    public static List<String> groupNames(JsRegExp regexp) {
        return List.copyOf(regexp.getGroupAliases().keySet());
    }

    // A duplicated name compiles to several java groups; at most one of them can have participated.
    public static String participatingGroup(JsRegExp regexp, String name, Matcher matcher) {
        final var aliases = regexp.getGroupAliases().get(name);
        if (aliases == null) {
            return name;
        }
        for (final var alias : aliases) {
            if (matcher.start(alias) >= 0) {
                return alias;
            }
        }
        return null;
    }

    private static JsValue groupValue(String value) {
        return value == null ? JsUndefined.getInstance() : new JsString(value);
    }

    private static String str(List<JsValue> args, InterpreterOps ops) {
        return args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst(), ops);
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    // Spec RegExpExec(R, S): dispatch through a (possibly user-overridden) "exec" own/inherited
    // property rather than matching internally, so overriding `exec` on a real JsRegExp changes the
    // behaviour of match/replace/search/split, matching the abstract operation's generality.
    public static JsValue regExpExec(JsValue rx, String s, InterpreterOps ops) {
        final var execFn = ops.getMember(rx, new JsString("exec"));
        if (isCallable(execFn)) {
            final var result = ops.call(execFn, rx, List.of(new JsString(s)));
            if (!(result instanceof JsObject) && !(result instanceof JsArray) && !(result instanceof JsNull)) {
                throw new TypeErrorException("RegExp exec method returned something other than an object or null");
            }
            return result;
        }
        if (rx instanceof JsRegExp regexp) {
            return builtinExec(rx, regexp, s, ops);
        }
        throw new TypeErrorException("RegExp.prototype.exec method is not generic");
    }

    // Spec RegExp.prototype[@@matchAll]: build a fresh matcher through SpeciesConstructor, seed its
    // lastIndex from the receiver's, and hand it to a %RegExpStringIteratorPrototype% iterator.
    public static JsValue symbolMatchAll(JsValue rx, String s, JsObject iteratorProto, JsObject regexpProto,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(rx)) {
            throw new TypeErrorException("RegExp.prototype[Symbol.matchAll] called on a non-object");
        }
        final var flags = JsCoercion.toStr(ops.getMember(rx, new JsString("flags")), ops);
        final var species = speciesConstructor(rx, regexpProto, ops);
        final var matcher = species == null
                ? RegexTranslator.compile(sourceOf(rx, ops), flags)
                : ops.construct(species, List.of(rx, new JsString(flags)));
        writeLastIndex(matcher, (int) toLength(readLastIndex(rx, ops), ops), ops);
        return createStringIterator(matcher, s, flags.indexOf('g') >= 0,
                flags.indexOf('u') >= 0 || flags.indexOf('v') >= 0, iteratorProto);
    }

    private static String sourceOf(JsValue rx, InterpreterOps ops) {
        return rx instanceof JsRegExp regexp
                ? regexp.getSource()
                : JsCoercion.toStr(ops.getMember(rx, new JsString("source")), ops);
    }

    private static JsValue speciesConstructor(JsValue rx, JsObject regexpProto, InterpreterOps ops) {
        final var constructor = ops.getMember(rx, new JsString("constructor"));
        if (constructor instanceof JsUndefined) {
            return defaultConstructor(regexpProto, ops);
        }
        if (!InterpreterUtils.isObjectLike(constructor)) {
            throw new TypeErrorException("constructor is not an object");
        }
        final var species = ops.getMember(constructor, JsSymbol.SPECIES);
        if (species instanceof JsUndefined || species instanceof JsNull) {
            return defaultConstructor(regexpProto, ops);
        }
        if (!InterpreterUtils.isConstructor(species)) {
            throw new TypeErrorException("Symbol.species is not a constructor");
        }
        return species;
    }

    private static JsValue defaultConstructor(JsObject regexpProto, InterpreterOps ops) {
        final var constructor = ops.getMember(regexpProto, new JsString("constructor"));
        return InterpreterUtils.isConstructor(constructor) ? constructor : null;
    }

    private static JsObject createStringIterator(JsValue matcher, String s, boolean global, boolean fullUnicode,
            JsObject proto) {
        final var iterator = new JsObject();
        iterator.setProto(proto);
        final var state = new JsObject();
        state.set("regexp", matcher);
        state.set("string", new JsString(s));
        state.set("global", JsBoolean.of(global));
        state.set("unicode", JsBoolean.of(fullUnicode));
        state.set("done", JsBoolean.FALSE);
        iterator.setSymbol(ITERATOR_STATE, state);
        return iterator;
    }

    public static JsValue stringIteratorNext(JsValue thisArg, InterpreterOps ops) {
        if (!(thisArg instanceof JsObject self) || !(self.getSymbol(ITERATOR_STATE) instanceof JsObject state)) {
            throw new TypeErrorException("next called on an incompatible receiver");
        }
        if (JsCoercion.toBoolean(state.get("done"))) {
            return iterationResult(JsUndefined.getInstance(), true);
        }
        final var rx = state.get("regexp");
        final var input = ((JsString) state.get("string")).getValue();
        final var match = regExpExec(rx, input, ops);
        if (match instanceof JsNull) {
            state.set("done", JsBoolean.TRUE);
            return iterationResult(JsUndefined.getInstance(), true);
        }
        if (!JsCoercion.toBoolean(state.get("global"))) {
            state.set("done", JsBoolean.TRUE);
            return iterationResult(match, false);
        }
        if (JsCoercion.toStr(ops.getMember(match, new JsString("0")), ops).isEmpty()) {
            final var index = toLength(readLastIndex(rx, ops), ops);
            setOrThrow(rx, new JsNumber(advanceStringIndex(input, index, JsCoercion.toBoolean(state.get("unicode")))),
                    ops);
        }
        return iterationResult(match, false);
    }

    private static JsObject iterationResult(JsValue value, boolean done) {
        final var result = new JsObject();
        result.set("value", value);
        result.set("done", JsBoolean.of(done));
        return result;
    }

    private static double advanceStringIndex(String s, double index, boolean unicode) {
        if (!unicode || index + 1 >= s.length()) {
            return index + 1;
        }
        final var at = (int) index;
        return Character.isHighSurrogate(s.charAt(at)) && Character.isLowSurrogate(s.charAt(at + 1))
                ? index + 2
                : index + 1;
    }

    // `global`/`unicode` come from one Get of "flags", not from three separate flag Gets: that single
    // read is what the spec makes observable.
    public static JsValue symbolMatch(JsValue rx, String s, InterpreterOps ops) {
        requireObject(rx, "Symbol.match");
        final var flags = JsCoercion.toStr(ops.getMember(rx, new JsString("flags")), ops);
        if (flags.indexOf('g') < 0) {
            return regExpExec(rx, s, ops);
        }
        final var fullUnicode = flags.indexOf('u') >= 0 || flags.indexOf('v') >= 0;
        writeLastIndex(rx, 0, ops);
        final var result = new JsArray();
        while (true) {
            final var match = regExpExec(rx, s, ops);
            if (match instanceof JsNull) {
                return result.length() == 0 ? JsNull.getInstance() : result;
            }
            final var matchStr = JsCoercion.toStr(ops.getMember(match, new JsString("0")), ops);
            result.push(new JsString(matchStr));
            if (matchStr.isEmpty()) {
                final var lastIndex = toLength(readLastIndex(rx, ops), ops);
                setOrThrow(rx, new JsNumber(advanceStringIndex(s, lastIndex, fullUnicode)), ops);
            }
        }
    }

    private static void requireObject(JsValue rx, String method) {
        if (!InterpreterUtils.isObjectLike(rx)) {
            throw new TypeErrorException("RegExp.prototype[" + method + "] called on a non-object");
        }
    }

    public static JsValue symbolSearch(JsValue rx, String s, InterpreterOps ops) {
        final var previousLastIndex = ops.getMember(rx, new JsString("lastIndex"));
        if (isNotSameValue(previousLastIndex, new JsNumber(0))) {
            setOrThrow(rx, new JsNumber(0), ops);
        }
        final var result = regExpExec(rx, s, ops);
        final var currentLastIndex = ops.getMember(rx, new JsString("lastIndex"));
        if (isNotSameValue(currentLastIndex, previousLastIndex)) {
            setOrThrow(rx, previousLastIndex, ops);
        }
        return result instanceof JsNull ? new JsNumber(-1) : ops.getMember(result, new JsString("index"));
    }

    // SameValue, not SameValueZero: @@search restores lastIndex only when it really changed, and -0
    // and +0 are different values to it.
    private static boolean isNotSameValue(JsValue left, JsValue right) {
        if (left instanceof JsNumber first && right instanceof JsNumber second) {
            return Double.compare(first.getValue(), second.getValue()) != 0;
        }
        return !SameValueZero.equal(left, right);
    }

    public static JsValue symbolReplace(JsValue rx, String s, JsValue replaceValue, InterpreterOps ops,
            Invoker invoker) {
        requireObject(rx, "Symbol.replace");
        final var functionalReplace = isCallable(replaceValue);
        final var replacementTemplate = functionalReplace ? null : JsCoercion.toStr(replaceValue, ops);
        final var flags = JsCoercion.toStr(ops.getMember(rx, new JsString("flags")), ops);
        final var global = flags.indexOf('g') >= 0;
        final var fullUnicode = global && (flags.indexOf('u') >= 0 || flags.indexOf('v') >= 0);
        if (global) {
            writeLastIndex(rx, 0, ops);
        }
        final var results = new ArrayList<JsValue>();
        while (true) {
            final var result = regExpExec(rx, s, ops);
            if (result instanceof JsNull) {
                break;
            }
            results.add(result);
            if (!global) {
                break;
            }
            final var matchStr = JsCoercion.toStr(ops.getMember(result, new JsString("0")), ops);
            if (matchStr.isEmpty()) {
                final var lastIndex = toLength(readLastIndex(rx, ops), ops);
                setOrThrow(rx, new JsNumber(advanceStringIndex(s, lastIndex, fullUnicode)), ops);
            }
        }
        final var accumulated = new StringBuilder();
        var nextSourcePosition = 0;
        for (final var result : results) {
            final var length = (int) JsCoercion.toNumber(ops.getMember(result, new JsString("length")), ops);
            final var nCaptures = Math.max(length - 1, 0);
            final var matched = JsCoercion.toStr(ops.getMember(result, new JsString("0")), ops);
            final var position = Math.clamp(
                    (long) JsCoercion.toNumber(ops.getMember(result, new JsString("index")), ops), 0, s.length());
            final var captures = new ArrayList<JsValue>();
            for (var n = 1; n <= nCaptures; n++) {
                final var capture = ops.getMember(result, new JsString(String.valueOf(n)));
                captures.add(capture instanceof JsUndefined ? capture : new JsString(JsCoercion.toStr(capture, ops)));
            }
            final var namedCaptures = ops.getMember(result, new JsString("groups"));
            final String replacement;
            if (functionalReplace) {
                final var replacerArgs = new ArrayList<JsValue>();
                replacerArgs.add(new JsString(matched));
                replacerArgs.addAll(captures);
                replacerArgs.add(new JsNumber(position));
                replacerArgs.add(new JsString(s));
                if (!(namedCaptures instanceof JsUndefined)) {
                    replacerArgs.add(namedCaptures);
                }
                replacement = JsCoercion.toStr(invoker.call(replaceValue, JsUndefined.getInstance(), replacerArgs),
                        ops);
            } else {
                // ToObject(namedCaptures) only happens on the template path, so a functional replacer
                // still receives a null groups value verbatim.
                if (namedCaptures instanceof JsNull) {
                    throw new TypeErrorException("Cannot convert the named-capture groups to an object");
                }
                replacement = getSubstitution(matched, s, position, captures, namedCaptures, replacementTemplate, ops);
            }
            if (position >= nextSourcePosition) {
                accumulated.append(s, nextSourcePosition, position).append(replacement);
                nextSourcePosition = position + matched.length();
            }
        }
        if (nextSourcePosition < s.length()) {
            accumulated.append(s, nextSourcePosition, s.length());
        }
        return new JsString(accumulated.toString());
    }

    private static String getSubstitution(String matched, String s, int position, List<JsValue> captures,
            JsValue namedCaptures, String template, InterpreterOps ops) {
        final var sb = new StringBuilder();
        for (var i = 0; i < template.length(); i++) {
            final var ch = template.charAt(i);
            if (ch != '$' || i + 1 >= template.length()) {
                sb.append(ch);
                continue;
            }
            final var next = template.charAt(i + 1);
            switch (next) {
                case '$' -> {
                    sb.append('$');
                    i++;
                }
                case '&' -> {
                    sb.append(matched);
                    i++;
                }
                case '`' -> {
                    sb.append(s, 0, position);
                    i++;
                }
                case '\'' -> {
                    sb.append(s.substring(position + matched.length()));
                    i++;
                }
                case '<' -> {
                    final var close = template.indexOf('>', i + 2);
                    // With no named captures (or no closing '>') the whole `$<` is literal text, so
                    // the '<' must not be consumed along with the '$'.
                    if (close < 0 || namedCaptures instanceof JsUndefined) {
                        sb.append(ch);
                    } else {
                        final var name = template.substring(i + 2, close);
                        final var value = ops.getMember(namedCaptures, new JsString(name));
                        if (!(value instanceof JsUndefined)) {
                            sb.append(JsCoercion.toStr(value, ops));
                        }
                        i = close;
                    }
                }
                default -> {
                    if (Character.isDigit(next)) {
                        i = appendCaptureGroup(sb, template, i, captures) - 1;
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static int appendCaptureGroup(StringBuilder sb, String template, int dollarIndex, List<JsValue> captures) {
        var end = dollarIndex + 2;
        if (end < template.length() && Character.isDigit(template.charAt(end))
                && Integer.parseInt(template.substring(dollarIndex + 1, end + 1)) <= captures.size()) {
            end++;
        }
        final var group = Integer.parseInt(template.substring(dollarIndex + 1, end));
        if (group >= 1 && group <= captures.size()) {
            final var value = captures.get(group - 1);
            if (!(value instanceof JsUndefined)) {
                sb.append(((JsString) value).getValue());
            }
            return end;
        }
        sb.append(template, dollarIndex, dollarIndex + 1);
        return dollarIndex + 1;
    }

    public static JsValue symbolSplit(JsValue rx, String s, JsValue limitValue, JsObject regexpProto,
            InterpreterOps ops) {
        requireObject(rx, "Symbol.split");
        final var species = speciesConstructor(rx, regexpProto, ops);
        final var flags = JsCoercion.toStr(ops.getMember(rx, new JsString("flags")), ops);
        final var unicodeMatching = flags.indexOf('u') >= 0 || flags.indexOf('v') >= 0;
        final var newFlags = flags.indexOf('y') >= 0 ? flags : flags + "y";
        final var splitter = species == null
                ? RegexTranslator.compile(sourceOf(rx, ops), newFlags)
                : ops.construct(species, List.of(rx, new JsString(newFlags)));
        final var result = new JsArray();
        final var limit = limitValue instanceof JsUndefined
                ? 0xFFFFFFFFL
                : ((long) JsCoercion.toNumber(limitValue, ops)) & 0xFFFFFFFFL;
        if (limit == 0) {
            return result;
        }
        final var length = s.length();
        if (length == 0) {
            if (!(regExpExec(splitter, s, ops) instanceof JsNull)) {
                return result;
            }
            result.push(new JsString(s));
            return result;
        }
        var p = 0;
        var q = 0;
        while (q < length) {
            writeLastIndex(splitter, q, ops);
            final var z = regExpExec(splitter, s, ops);
            if (z instanceof JsNull) {
                q = (int) advanceStringIndex(s, q, unicodeMatching);
                continue;
            }
            final var e = Math.min((int) JsCoercion.toNumber(ops.getMember(splitter, new JsString("lastIndex")), ops),
                    length);
            if (e == p) {
                q = (int) advanceStringIndex(s, q, unicodeMatching);
                continue;
            }
            result.push(new JsString(s.substring(p, q)));
            if (result.length() == limit) {
                return result;
            }
            final var groupCount = (int) JsCoercion.toNumber(ops.getMember(z, new JsString("length")), ops) - 1;
            for (var i = 1; i <= groupCount; i++) {
                result.push(ops.getMember(z, new JsString(String.valueOf(i))));
                if (result.length() == limit) {
                    return result;
                }
            }
            p = e;
            q = p;
        }
        result.push(new JsString(s.substring(p, length)));
        return result;
    }
}

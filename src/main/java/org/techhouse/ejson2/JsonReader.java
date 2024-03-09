package org.techhouse.ejson2;

import org.techhouse.ejson2.elements.*;
import org.techhouse.ejson2.exceptions.MalformedJsonException;
import org.techhouse.ejson2.exceptions.NoConstructorException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public class JsonReader {

    public <T> T fromJson(String input, Class<T> tClass) throws InstantiationException, IllegalAccessException, InvocationTargetException {
        final var tokens = Lexer.lex(input);
        final var parsed = internalParse(tokens, true);
        final var constructor = getNoArgsConstructor(tClass);
        final var newInstance = constructor.newInstance();
        assign(newInstance, parsed);
        return tClass.cast(newInstance);
    }

    private JsonBaseElement internalParse(List<JsonBaseElement> tokens, boolean isRoot) {
        final var firstToken = tokens.getFirst();
        if (isRoot && (!firstToken.equals(JsonSyntaxToken.LEFT_BRACE) && !firstToken.equals(JsonSyntaxToken.LEFT_BRACKET))) {
            throw new MalformedJsonException("Json must start with either a left bracket or a left brace");
        }
        if (firstToken.equals(JsonSyntaxToken.LEFT_BRACKET)) {
            return parseArray(skipOneToken(tokens));
        } else if (firstToken.equals(JsonSyntaxToken.LEFT_BRACE)) {
            return parseObject(skipOneToken(tokens));
        } else {
            return firstToken;
        }
    }

    private <T> void findMethodAndAssign(Method[] methods, String fieldName, T obj, JsonBaseElement element) throws InvocationTargetException, IllegalAccessException {
        final var foundMethod = Arrays.stream(methods).filter(x -> x.getName().equalsIgnoreCase("set" + fieldName)).findFirst();
        if (foundMethod.isPresent()) {
            var value = switch (element.getJsonType()) {
                case BOOLEAN -> ((JsonBoolean) element).getValue();
                case NULL -> null;
                case STRING -> ((JsonString) element).getValue();
                case DOUBLE -> ((JsonDouble) element).getValue();
                default -> null; // Should never enter here
            };
            foundMethod.get().invoke(obj, value);
        }
    }

    private <T> void assign(T obj, JsonBaseElement parsed) throws InvocationTargetException, IllegalAccessException, InstantiationException {
        final var type = parsed.getJsonType();
        final var clazz = obj.getClass();
        final var fields = clazz.getDeclaredFields();
        final var methods = clazz.getDeclaredMethods();
        for (var field : fields) {
            final var fieldName = field.getName();
            if (type == JsonBaseElement.JsonType.OBJECT) {
                final var jsonObj = (JsonObject) parsed;
                if (jsonObj.has(fieldName)) {
                    final var fieldValue = jsonObj.get(fieldName);
                    var objField = field.get(obj);
                    if (objField == null) {
                        final var objFieldConstructor = getNoArgsConstructor(field.getType());
                        objField = objFieldConstructor.newInstance();
                        field.set(obj, fieldValue);
                    }
                    assign(objField, fieldValue);
                }
            } else if (type == JsonBaseElement.JsonType.ARRAY) {
                final var arr = (JsonArray) parsed;
                for (var item : arr) {
                    // TODO: assign items to collection
                }
            } else {
                findMethodAndAssign(methods, fieldName, obj, parsed);
            }
        }
    }

    private JsonObject parseObject(List<JsonBaseElement> tokens) {
        final var obj = new JsonObject();
        var firstToken = tokens.getFirst();
        if (firstToken.equals(JsonSyntaxToken.RIGHT_BRACE)) {
            return obj;
        }
        for (int i = 0; i < 100; i++) {
            firstToken = tokens.getFirst();
            var propertyName = "";
            if (firstToken.getJsonType() == JsonBaseElement.JsonType.STRING) {
                propertyName = ((JsonString) firstToken).getValue();
                tokens = skipOneToken(tokens);
            } else {
                throw new MalformedJsonException("Expected string key, got " + firstToken.getJsonType());
            }
            firstToken = tokens.getFirst();
            if (!firstToken.equals(JsonSyntaxToken.COLON)) {
                throw new MalformedJsonException("Expected colon after key in object, got: " + firstToken.getJsonType());
            }
            tokens = skipOneToken(tokens);
            final var parsedValue = internalParse(tokens, false);
            obj.add(propertyName, parsedValue);
            tokens = skipTokens(tokens, parsedValue);
            firstToken = tokens.getFirst();
            if (firstToken.equals(JsonSyntaxToken.RIGHT_BRACE)) {
                return obj;
            } else if (!firstToken.equals(JsonSyntaxToken.COMMA)) {
                throw new MalformedJsonException("Expected comma after pair in object, got: " + firstToken.getJsonType());
            }
            tokens = skipOneToken(tokens);
        }
        throw new MalformedJsonException("Expected end-of-object bracket");
    }

    private JsonArray parseArray(List<JsonBaseElement> tokens) {
        final var arr = new JsonArray();
        var firstToken = tokens.getFirst();
        if (firstToken.equals(JsonSyntaxToken.RIGHT_BRACE)) {
            return arr;
        }
        while (true) {
            final var parsed = internalParse(tokens, false);
            arr.add(parsed);
            if (firstToken.equals(JsonSyntaxToken.RIGHT_BRACE)) {
                return arr;
            } else if (firstToken.equals(JsonSyntaxToken.COMMA)) {
                throw new MalformedJsonException("Expected comma after object in array");
            } else {
                tokens = skipOneToken(tokens);
            }
        }
//        throw new MalformedJsonException("Expected end-of-array bracket");
    }

    private List<JsonBaseElement> skipOneToken(List<JsonBaseElement> tokens) {
        return tokens.subList(1, tokens.size());
    }

    private List<JsonBaseElement> skipTokens(List<JsonBaseElement> tokens, JsonBaseElement element) {
        return tokens.subList(getTokenSize(element), tokens.size());
    }

    private int getArrTokenSize(JsonArray arr) {
        var count = 1; // +1 [ open array
        for (var i = 0; i < arr.size(); i++) {
            count += getTokenSize(arr.get(i));
            if (i < arr.size()) {
                count++; // +1 , arr separator
            }
        }
        count++; // +1 ] close array
        return count;
    }

    private int getTokenSize(JsonBaseElement element) {
        var count = 0;
        switch (element.getJsonType()) {
            case BOOLEAN, NULL, STRING, DOUBLE, SYNTAX -> count += 1;
            case ARRAY -> {
                final var arr = (JsonArray) element;
                count += getArrTokenSize(arr);
            }
            case OBJECT -> {
                count++; // +1 { open object
                final var obj = (JsonObject) element;
                final var entryList = obj.entrySet().stream().toList();
                final var size = entryList.size();
                for (var i = 0; i < size; i++) {
                    final var entry = entryList.get(i);
                    final var value = entry.getValue();
                    switch (value.getJsonType()) {
                        case SYNTAX, BOOLEAN, DOUBLE, NULL, STRING -> count += 3;
                        case ARRAY -> {
                            final var arr = (JsonArray) value;
                            count += getArrTokenSize(arr);
                        }
                        case OBJECT -> count += getTokenSize(obj);
                    }
                    if (i < size - 1) {
                        count++; // +1 , member separator
                    }
                }
                count++; // +1 } close object
            }
        }
        return count;
    }

    private <T> Constructor<?> getNoArgsConstructor(Class<T> tClass) {
        final var constructors = tClass.getDeclaredConstructors();
        for (var constructor : constructors) {
            if (constructor.getParameters().length == 0 && constructor.canAccess(this)) {
                return constructor;
            }
        }
        throw new NoConstructorException(tClass);
    }
}

package org.techhouse.unit.ejson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.ReflectionUtils;
import org.techhouse.ejson.internal.UnsafeAllocator;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.mid_operators.BaseMidOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.ops.resp.OperationResponse;

final class WireFixtures {
    private static final String DOCUMENT_JSON = "{\"s\":\"a\\\"b\\\\c\\n\\t\\u0001é€\",\"n\":1.5,\"i\":3,"
            + "\"t\":true,\"f\":false,\"z\":null,\"o\":{\"k\":[1,\"x\",{}],\"e\":{}},\"a\":[],\"g\":\"#geo(10.5,20.5)\"}";
    private static final String ARRAY_JSON = "[1,\"two\",null,true,{\"k\":\"v\"},[]]";
    private static final List<Class<?>> WIRE_ROOTS = List.of(OperationRequest.class, OperationResponse.class,
            BaseAggregationStep.class, BaseOperator.class, BaseMidOperator.class, MapOperator.class);
    private static final int MAX_DEPTH = 6;
    private static final String TYPE_SUFFIXES = "(Request|Response|AggregationStep|MapOperator|MidOperator|Operator)$";

    private WireFixtures() {
    }

    static List<Class<?>> wireClasses() {
        final var classes = new ArrayList<Class<?>>();
        final var root = classesRoot();
        for (final var packagePath : List.of("org/techhouse/ops/req", "org/techhouse/ops/resp")) {
            try (Stream<Path> walk = Files.walk(root.resolve(packagePath))) {
                walk.filter(path -> path.toString().endsWith(".class") && !path.toString().contains("$"))
                        .map(path -> classNamed(root, path)).filter(WireFixtures::isWireClass).forEach(classes::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        classes.sort(Comparator.comparing(Class::getName));
        return classes;
    }

    static String wireLine(Class<?> type) throws Exception {
        return type.getName() + "\t" + new EJson().toJson(filled(type, 0));
    }

    static JsonObject document() {
        return new EJson().fromJson(DOCUMENT_JSON, JsonObject.class);
    }

    static JsonArray array() {
        return new EJson().fromJson("{\"a\":" + ARRAY_JSON + "}", JsonObject.class).get("a").asJsonArray();
    }

    private static Path classesRoot() {
        try {
            return Path.of(OperationRequest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?> classNamed(Path root, Path classFile) {
        final var relative = root.relativize(classFile).toString();
        final var name = relative.substring(0, relative.length() - ".class".length()).replace('/', '.').replace('\\',
                '.');
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isWireClass(Class<?> type) {
        final var modifiers = type.getModifiers();
        return !Modifier.isAbstract(modifiers) && !type.isEnum() && !type.isInterface()
                && WIRE_ROOTS.stream().anyMatch(wireRoot -> wireRoot.isAssignableFrom(type));
    }

    private static Object filled(Class<?> type, int depth) throws Exception {
        final var instance = UnsafeAllocator.INSTANCE.newInstance(type);
        final var fields = ReflectionUtils.getFields(type);
        for (var index = 0; index < fields.length; index++) {
            final var field = fields[index];
            ReflectionUtils.setFieldValue(field, instance,
                    valueFor(field.getType(), field.getGenericType(), field.getName(), index, depth, type));
        }
        return instance;
    }

    private static Object valueFor(Class<?> type, Type genericType, String name, int index, int depth, Class<?> owner)
            throws Exception {
        if (type == String.class) {
            return name;
        }
        if (type == boolean.class || type == Boolean.class) {
            return true;
        }
        if (type.isEnum()) {
            return enumConstantFor(type, name, owner);
        }
        final var number = numberFor(type, index);
        if (number != null) {
            return number;
        }
        if (JsonBaseElement.class.isAssignableFrom(type)) {
            return type == JsonArray.class ? array() : document();
        }
        if (List.class.isAssignableFrom(type)) {
            return listFor((ParameterizedType) genericType, name, depth);
        }
        if (depth >= MAX_DEPTH) {
            throw new IllegalStateException("Fixture nesting too deep at " + name);
        }
        return filled(type, depth + 1);
    }

    private static Object enumConstantFor(Class<?> enumType, String fieldName, Class<?> owner) {
        final var constants = enumType.getEnumConstants();
        if ("type".equals(fieldName)) {
            final var wanted = upperSnake(owner.getSimpleName().replaceFirst(TYPE_SUFFIXES, ""));
            for (final var constant : constants) {
                if (((Enum<?>) constant).name().equals(wanted)) {
                    return constant;
                }
            }
        }
        return constants[0];
    }

    private static String upperSnake(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static Object numberFor(Class<?> type, int index) {
        if (type == int.class || type == Integer.class) {
            return index;
        }
        if (type == long.class || type == Long.class) {
            return (long) index;
        }
        if (type == double.class || type == Double.class) {
            return (double) index;
        }
        return null;
    }

    private static List<Object> listFor(ParameterizedType genericType, String name, int depth) throws Exception {
        final var itemClass = (Class<?>) genericType.getActualTypeArguments()[0];
        final var items = new ArrayList<>();
        for (var i = 0; i < 2; i++) {
            items.add(valueFor(itemClass, null, name + i, i, depth, itemClass));
        }
        return items;
    }
}

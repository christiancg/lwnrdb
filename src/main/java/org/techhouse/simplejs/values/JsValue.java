package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.List;

public abstract class JsValue {
    public enum JsValueType {
        NUMBER, STRING, BOOLEAN, BIGINT, UNDEFINED, NULL, OBJECT, ARRAY, FUNCTION, CLASS, PROMISE, GENERATOR, ASYNC_GENERATOR, REGEXP, SYMBOL, MAP, SET, DATE, PROXY, ARGUMENTS, GLOBAL, ARRAY_BUFFER, TYPED_ARRAY, DATA_VIEW, TEMPORAL_DURATION, TEMPORAL_PLAIN_TIME, TEMPORAL_PLAIN_DATE, TEMPORAL_INSTANT, TEMPORAL_PLAIN_YEAR_MONTH, TEMPORAL_PLAIN_MONTH_DAY, TEMPORAL_PLAIN_DATE_TIME, TEMPORAL_ZONED_DATE_TIME, GEO, VECTOR, DB_DATE_TIME, DB_TIME
    }

    public JsValueType getType() {
        return internalGetType(this);
    }

    // The ordinary-object substrate every non-primitive value type carries. A primitive answers
    // null, which is what distinguishes it from an object at every property choke point.
    public PropertyTable ownProperties() {
        return null;
    }

    // [[Prototype]] is any object-like value, not just a plain JsObject: `foo.prototype = [1, 2]`
    // and Object.setPrototypeOf(o, someArray) both link a chain that has to stay walkable.
    public JsValue getProto() {
        return null;
    }

    public void setProto(JsValue proto) {
        // A value type without a [[Prototype]] slot silently ignores the link.
    }

    public boolean isExtensible() {
        final var properties = ownProperties();
        return properties != null && properties.isExtensible();
    }

    // The five ordinary-object operations. Every default answers from ownProperties(), so a value
    // type whose keys live elsewhere (an array's indices, the global object's Environment) overrides
    // the ones it owns instead of the choke points special-casing it. A proxy never reaches these:
    // ProxyDispatch intercepts in front of them.
    public List<JsValue> ownPropertyKeys() {
        final var table = ownProperties();
        if (table == null) {
            return List.of();
        }
        final var keys = new ArrayList<JsValue>();
        if (this instanceof JsCallableProperties callable) {
            for (final var key : OrdinaryProperties.metadataKeys(callable)) {
                keys.add(new JsString(key));
            }
            for (final var key : table.keys()) {
                if (!OrdinaryProperties.metadataKey(callable, key)) {
                    keys.add(new JsString(key));
                }
            }
        } else {
            for (final var key : table.keys()) {
                keys.add(new JsString(key));
            }
        }
        keys.addAll(table.symbolKeys());
        return keys;
    }

    public PropertyDescriptor getOwnProperty(JsValue key) {
        final var table = ownProperties();
        if (table == null) {
            return null;
        }
        if (key instanceof JsSymbol symbol) {
            final var symbolSlot = OrdinaryProperties.symbolSlot(table, symbol);
            return symbolSlot.exists() ? OrdinaryProperties.describe(symbolSlot) : null;
        }
        final var name = OrdinaryProperties.keyName(key);
        final var slot = OrdinaryProperties.stringSlot(table, name);
        if (slot.exists()) {
            return OrdinaryProperties.describe(slot);
        }
        return this instanceof JsCallableProperties callable && OrdinaryProperties.metadataKey(callable, name)
                ? OrdinaryProperties.metadataDescriptor(this, callable, name)
                : null;
    }

    // Rejections are raised as a TypeError with the offending key rather than reported through the
    // return value, which says only whether this value owns the definition at all.
    public boolean defineOwnProperty(JsValue key, PropertyDescriptor descriptor) {
        final var table = ownProperties();
        if (table == null) {
            return false;
        }
        if (key instanceof JsSymbol symbol) {
            OrdinaryProperties.validateAndApply(OrdinaryProperties.symbolSlot(table, symbol), isExtensible(),
                    symbol.getDescription(), descriptor);
            return true;
        }
        final var name = OrdinaryProperties.keyName(key);
        OrdinaryProperties.materialiseMetadata(this, table, name);
        OrdinaryProperties.validateAndApply(OrdinaryProperties.stringSlot(table, name), isExtensible(), name,
                descriptor);
        return true;
    }

    // [[Delete]] of a property that isn't there succeeds, which is why a primitive answers true. A
    // lazily-materialised metadata property (a callable's non-configurable "name"/"length", a
    // builtin's "prototype", ...) is absent from the table until defineOwnProperty first touches it,
    // so `table.delete` alone would see nothing there and trivially succeed - materialising it first
    // (as defineOwnProperty already does) makes the real, possibly non-configurable flags the ones
    // consulted.
    public boolean deleteOwnProperty(JsValue key) {
        final var table = ownProperties();
        if (table == null) {
            return true;
        }
        if (key instanceof JsSymbol symbol) {
            return !table.isNotDeleteSymbol(symbol);
        }
        final var name = OrdinaryProperties.keyName(key);
        OrdinaryProperties.materialiseMetadata(this, table, name);
        return table.delete(name);
    }

    public boolean hasOwnKey(JsValue key) {
        return getOwnProperty(key) != null;
    }

    private static JsValueType internalGetType(Object object) {
        return switch (object) {
            case JsNumber ignored -> JsValueType.NUMBER;
            case JsString ignored -> JsValueType.STRING;
            case JsBoolean ignored -> JsValueType.BOOLEAN;
            case JsBigInt ignored -> JsValueType.BIGINT;
            case JsUndefined ignored -> JsValueType.UNDEFINED;
            case JsNull ignored -> JsValueType.NULL;
            case JsObject ignored -> JsValueType.OBJECT;
            case JsArray ignored -> JsValueType.ARRAY;
            case JsFunction ignored -> JsValueType.FUNCTION;
            case JsNativeFunction ignored -> JsValueType.FUNCTION;
            case JsClass ignored -> JsValueType.CLASS;
            case JsPromise ignored -> JsValueType.PROMISE;
            case JsGenerator ignored -> JsValueType.GENERATOR;
            case JsAsyncGenerator ignored -> JsValueType.ASYNC_GENERATOR;
            case JsRegExp ignored -> JsValueType.REGEXP;
            case JsSymbol ignored -> JsValueType.SYMBOL;
            case JsMap ignored -> JsValueType.MAP;
            case JsSet ignored -> JsValueType.SET;
            case JsDate ignored -> JsValueType.DATE;
            case JsTemporalDuration ignored -> JsValueType.TEMPORAL_DURATION;
            case JsProxy ignored -> JsValueType.PROXY;
            case JsArguments ignored -> JsValueType.ARGUMENTS;
            case JsGlobalObject ignored -> JsValueType.GLOBAL;
            case JsArrayBuffer ignored -> JsValueType.ARRAY_BUFFER;
            case JsTypedArray ignored -> JsValueType.TYPED_ARRAY;
            case JsDataView ignored -> JsValueType.DATA_VIEW;
            case JsTemporalPlainTime ignored -> JsValueType.TEMPORAL_PLAIN_TIME;
            case JsTemporalPlainDate ignored -> JsValueType.TEMPORAL_PLAIN_DATE;
            case JsTemporalInstant ignored -> JsValueType.TEMPORAL_INSTANT;
            case JsTemporalPlainYearMonth ignored -> JsValueType.TEMPORAL_PLAIN_YEAR_MONTH;
            case JsTemporalPlainMonthDay ignored -> JsValueType.TEMPORAL_PLAIN_MONTH_DAY;
            case JsTemporalPlainDateTime ignored -> JsValueType.TEMPORAL_PLAIN_DATE_TIME;
            case JsTemporalZonedDateTime ignored -> JsValueType.TEMPORAL_ZONED_DATE_TIME;
            case JsGeo ignored -> JsValueType.GEO;
            case JsVector ignored -> JsValueType.VECTOR;
            case JsDbDateTime ignored -> JsValueType.DB_DATE_TIME;
            case JsDbTime ignored -> JsValueType.DB_TIME;
            default -> throw new IllegalStateException("Unexpected value: " + object);
        };
    }
}

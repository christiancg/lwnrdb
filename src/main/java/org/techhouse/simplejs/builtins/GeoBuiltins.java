package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.requireNewTarget;
import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;

import java.util.List;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsGeo;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.utils.GeoPoint;
import org.techhouse.utils.GeoUtils;

public final class GeoBuiltins {
    public static final List<String> NAMES = List.of("toString", "toJSON");
    public static final List<String> FIELD_ACCESSORS = List.of("lat", "lng", "geoHash");

    private static final int GEO_HASH_PRECISION = 12;

    private GeoBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("Geo", (thisArg, args) -> {
            requireNewTarget("Geo", thisArg);
            return withNewTargetPrototype(new JsGeo(construct(args, ops)), ops);
        });
        final var from = new JsNativeFunction("from", (_, args) -> new JsGeo(toGeoPoint(arg(args, 0), ops)));
        from.setLength(1);
        ctor.setProperty("from", from);
        return ctor;
    }

    private static GeoPoint construct(List<JsValue> args, InterpreterOps ops) {
        return point(JsCoercion.toNumber(arg(args, 0), ops), JsCoercion.toNumber(arg(args, 1), ops));
    }

    private static GeoPoint point(double lat, double lng) {
        if (Double.isNaN(lat) || lat < -90 || lat > 90) {
            throw new RangeErrorException("latitude must be in the range -90..90, got " + lat);
        }
        if (Double.isNaN(lng) || lng < -180 || lng > 180) {
            throw new RangeErrorException("longitude must be in the range -180..180, got " + lng);
        }
        return new GeoPoint(lat, lng);
    }

    private static GeoPoint toGeoPoint(JsValue value, InterpreterOps ops) {
        if (value instanceof JsGeo geo) {
            return geo.getPoint();
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsGeo wrapped) {
            return wrapped.getPoint();
        }
        if (value instanceof JsString text) {
            return parse(text.getValue());
        }
        if (InterpreterUtils.isObjectLike(value)) {
            return point(JsCoercion.toNumber(member(value, "lat", ops), ops),
                    JsCoercion.toNumber(member(value, "lng", ops), ops));
        }
        throw new TypeErrorException(
                "Cannot convert to Geo: expected a Geo, a '#geo(lat,lng)' string or a {lat, lng} " + "object");
    }

    private static GeoPoint parse(String text) {
        try {
            return new JsonGeo(text).point();
        } catch (RuntimeException e) {
            throw new RangeErrorException("Invalid geo string: '" + text + "'");
        }
    }

    private static JsValue member(JsValue target, String name, InterpreterOps ops) {
        if (ops == null) {
            return target instanceof JsObject object ? object.get(name) : JsUndefined.getInstance();
        }
        return ops.getMember(target, new JsString(name));
    }

    public static void installAccessors(JsObject proto) {
        for (final var name : FIELD_ACCESSORS) {
            final var getter = new JsNativeFunction("get " + name,
                    (thisArg, _) -> fieldAccessor(requireReceiver(thisArg, name), name));
            getter.setLength(0);
            proto.defineAccessor(name, getter, null);
            proto.setFlags(name, JsObject.PropertyFlags.HIDDEN);
        }
    }

    private static JsGeo requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsGeo geo) {
            return geo;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsGeo wrapped) {
            return wrapped;
        }
        throw new TypeErrorException("Geo.prototype." + method + " called on an incompatible receiver");
    }

    public static JsValue fieldAccessor(JsGeo receiver, String name) {
        return switch (name) {
            case "lat" -> new JsNumber(receiver.getPoint().lat());
            case "lng" -> new JsNumber(receiver.getPoint().lng());
            case "geoHash" -> new JsString(geoHash(receiver));
            default -> null;
        };
    }

    private static String geoHash(JsGeo receiver) {
        return GeoUtils.geoHash(receiver.getPoint().lat(), receiver.getPoint().lng(), GEO_HASH_PRECISION);
    }

    public static JsValue getMethod(JsGeo receiver, String name) {
        return switch (name) {
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(receiver.toString()));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            default -> null;
        };
    }
}

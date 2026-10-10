package org.techhouse.simplejs.values;

import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.utils.GeoPoint;

public final class JsGeo extends JsValue {
    private PropertyTable table;

    private final GeoPoint point;

    private final JsonGeo source;

    public JsGeo(GeoPoint point) {
        this(point, null);
    }

    public JsGeo(JsonGeo source) {
        this(source.point(), source);
    }

    private JsGeo(GeoPoint point, JsonGeo source) {
        this.point = point;
        this.source = source;
    }

    public GeoPoint getPoint() {
        return point;
    }

    public JsGeo copy() {
        return new JsGeo(point, source);
    }

    public JsonGeo toJsonGeo() {
        return source != null ? source : new JsonGeo(point);
    }

    @Override
    public String toString() {
        return new JsonGeo(point).getValue();
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }

    @Override
    public JsValueType getType() {
        return JsValueType.GEO;
    }
}

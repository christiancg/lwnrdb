package org.techhouse.simplejs.values;

import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.utils.GeoPoint;

public final class JsGeo extends JsValue {
    private PropertyTable table;

    private final GeoPoint point;

    public JsGeo(GeoPoint point) {
        this.point = point;
    }

    public GeoPoint getPoint() {
        return point;
    }

    public JsonGeo toJsonGeo() {
        return new JsonGeo(point);
    }

    @Override
    public String toString() {
        return toJsonGeo().getValue();
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}

package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsDbDateTime;
import org.techhouse.simplejs.values.JsGeo;
import org.techhouse.simplejs.values.JsVector;
import org.techhouse.utils.GeoPoint;

public class EJsonInteropCustomSpellingTest {
    private static String roundTrip(JsonBaseElement element) {
        return EJsonInterop.toHostEjson(EJsonInterop.fromEjson(element)).asJsonString().getValue();
    }

    @Test
    public void geoRoundTripKeepsIntegralSpelling() {
        assertEquals("#geo(45,-122)", roundTrip(new JsonGeo("#geo(45,-122)")));
    }

    @Test
    public void vectorRoundTripKeepsSpelling() {
        assertEquals("#vector(1,2,1e+20)", roundTrip(new JsonVector("#vector(1,2,1e+20)")));
    }

    @Test
    public void dateTimeRoundTripKeepsSeconds() {
        assertEquals("#datetime(2024-01-01T10:00:00)", roundTrip(new JsonDateTime("#datetime(2024-01-01T10:00:00)")));
    }

    @Test
    public void timeRoundTripKeepsSeconds() {
        assertEquals("#time(10:00:00)", roundTrip(new JsonTime("#time(10:00:00)")));
    }

    @Test
    public void roundTripReturnsSourceNestedInObjectAndArray() {
        final var array = new JsonArray();
        array.add(new JsonGeo("#geo(1,2)"));
        final var document = new JsonObject();
        document.add("at", new JsonTime("#time(10:00:00)"));
        document.add("points", array);

        final var converted = EJsonInterop.toHostEjson(EJsonInterop.fromEjson(document)).asJsonObject();

        assertEquals("#time(10:00:00)", converted.get("at").asJsonString().getValue());
        assertEquals("#geo(1,2)", converted.get("points").asJsonArray().get(0).asJsonString().getValue());
    }

    @Test
    public void scriptConstructedGeoKeepsDerivedSpelling() {
        assertEquals("#geo(45.0,-122.0)", new JsGeo(new GeoPoint(45, -122)).toJsonGeo().getValue());
        assertEquals("#vector(1.0,2.0)", new JsVector(new double[]{1, 2}).toJsonVector().getValue());
    }

    @Test
    public void toStringIsIndependentOfSource() {
        assertEquals(new JsGeo(new GeoPoint(45, -122)).toString(), new JsGeo(new JsonGeo("#geo(45,-122)")).toString());
        assertEquals(new JsDbDateTime(LocalDateTime.of(2024, 1, 1, 10, 0)).toString(),
                new JsDbDateTime(new JsonDateTime("#datetime(2024-01-01T10:00:00)")).toString());
    }

    @Test
    public void copyKeepsSource() {
        assertEquals("#geo(45,-122)", new JsGeo(new JsonGeo("#geo(45,-122)")).copy().toJsonGeo().getValue());
        assertEquals("#vector(1,2)", new JsVector(new JsonVector("#vector(1,2)")).copy().toJsonVector().getValue());
    }
}

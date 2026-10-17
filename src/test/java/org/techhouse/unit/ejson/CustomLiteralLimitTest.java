package org.techhouse.unit.ejson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestUtils;

public class CustomLiteralLimitTest {
    private static final int WIRE_BUDGET = 1_000_000;
    private final Configuration configuration = Configuration.getInstance();

    @AfterEach
    void restore() throws Exception {
        TestUtils.setPrivateField(configuration, "maxVectorDimensions", ConfigKey.MAX_VECTOR_DIMENSIONS.defaultValue());
    }

    private static String components(int count) {
        return "0,".repeat(count - 1) + "0";
    }

    @Test
    public void test_a_wire_vector_at_the_dimension_limit_is_accepted() throws Exception {
        TestUtils.setPrivateField(configuration, "maxVectorDimensions", 8);
        final var request = RequestParser.parseRequest(save(components(8)), WIRE_BUDGET);
        assertEquals(8, ((JsonVector) ((SaveRequest) request).getObject().get("v")).getCustomValue().length);
    }

    @Test
    public void test_a_wire_vector_past_the_dimension_limit_is_not_a_valid_command() throws Exception {
        TestUtils.setPrivateField(configuration, "maxVectorDimensions", 8);
        assertThrows(InvalidCommandException.class, () -> RequestParser.parseRequest(save(components(9)), WIRE_BUDGET));
    }

    @Test
    public void test_vector_components_count_toward_the_token_budget() {
        final var line = save(components(500));
        assertThrows(InvalidCommandException.class, () -> RequestParser.parseRequest(line, 100));
    }

    @Test
    public void test_a_stored_vector_past_the_limit_stays_readable() throws Exception {
        TestUtils.setPrivateField(configuration, "maxVectorDimensions", 8);
        final var stored = new EJson().fromJson("{\"_id\":\"a\",\"v\":\"#vector(" + components(9) + ")\"}",
                JsonObject.class);
        assertEquals(9, ((JsonVector) stored.get("v")).getCustomValue().length);
    }

    private static String save(String vectorComponents) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"db1\",\"collectionName\":\"c1\",\"object\":{\"_id\":\"a\","
                + "\"v\":\"#vector(" + vectorComponents + ")\"}}";
    }

    @Test
    public void test_a_stored_geo_with_a_trailing_comma_stays_readable() {
        assertEquals(2.0, new JsonGeo("#geo(1,2,)").getCustomValue().lng());
        assertEquals(1.0, new JsonGeo("#geo(1,2,,)").getCustomValue().lat());
    }

    @Test
    public void test_a_geo_literal_with_extra_components_is_refused() {
        final var overlong = "#geo(1," + "2,".repeat(1000) + "3)";
        assertThrows(WrongFormatCustomTypeException.class, () -> new JsonGeo(overlong));
        assertEquals(1.5, new JsonGeo("#geo(1.5,2.5)").getCustomValue().lat());
        assertEquals(1.0, new JsonGeo("#geo(1" + ".0".repeat(1) + "0".repeat(200) + ",2)").getCustomValue().lat());
    }
}

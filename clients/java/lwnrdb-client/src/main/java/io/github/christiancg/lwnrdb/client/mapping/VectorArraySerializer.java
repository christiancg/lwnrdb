package io.github.christiancg.lwnrdb.client.mapping;

import io.github.christiancg.lwnrdb.client.internal.CustomValues;
import io.github.christiancg.lwnrdb.client.values.Vector;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

public final class VectorArraySerializer extends ValueSerializer<double[]> {
    @Override
    public void serialize(double[] value, JsonGenerator gen, SerializationContext context) {
        gen.writeString(CustomValues.format(Vector.of(value)));
    }
}

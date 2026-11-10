package io.github.christiancg.lwnrdb.client.values;

import java.util.Arrays;

public final class Vector {
    private final double[] components;

    private Vector(double[] components) {
        if (components.length == 0) {
            throw new IllegalArgumentException("a vector needs at least one component");
        }
        for (var i = 0; i < components.length; i++) {
            if (!Double.isFinite(components[i])) {
                throw new IllegalArgumentException("vector component " + i + " is not finite: " + components[i]);
            }
        }
        this.components = components;
    }

    public static Vector of(double... components) {
        return new Vector(components.clone());
    }

    public int dimensions() {
        return components.length;
    }

    public double component(int index) {
        return components[index];
    }

    public double[] toArray() {
        return components.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Vector vector && Arrays.equals(components, vector.components);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(components);
    }

    @Override
    public String toString() {
        return "Vector" + Arrays.toString(components);
    }
}

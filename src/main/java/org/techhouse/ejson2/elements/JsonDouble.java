package org.techhouse.ejson2.elements;

import lombok.Getter;

@Getter
public class JsonDouble extends JsonPrimitive<Double> {
    private int strLength;
    public JsonDouble() {
        this.value = 0.0;
    }

    public JsonDouble(Double value, int strLength) {
        this.value = value;
        this.strLength = strLength;
    }

}

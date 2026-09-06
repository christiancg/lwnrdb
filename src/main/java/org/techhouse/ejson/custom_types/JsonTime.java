package org.techhouse.ejson.custom_types;

import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;

public class JsonTime extends JsonCustom<LocalTime> {
    public static final String CUSTOM_TYPE_NAME = "time";

    public JsonTime(LocalTime customValue) {
        super(customValue);
    }

    public JsonTime(String strValue) {
        super(strValue);
    }

    public JsonTime() {
        super();
    }

    @Override
    public String getCustomTypeName() {
        return CUSTOM_TYPE_NAME;
    }

    @Override
    protected LocalTime parse() {
        try {
            return LocalTime.parse(stringDataValue());
        } catch (Exception e) {
            throw new WrongFormatCustomTypeException(getClass().getName(), e);
        }
    }

    @Override
    public Integer compare(LocalTime another) {
        return customValue.compareTo(another);
    }

    @Override
    public Set<String> customOperatorNames() {
        return Set.of();
    }

    @Override
    public boolean applyCustomOperator(String operatorName, Map<String, JsonBaseElement> args) {
        throw new UnsupportedOperationException(getCustomTypeName() + " has no custom operators");
    }

    @Override
    public Set<String> customRankingOperatorNames() {
        return Set.of();
    }

    @Override
    public double applyCustomRankingOperator(String operatorName, Map<String, JsonBaseElement> args) {
        throw new UnsupportedOperationException(getCustomTypeName() + " has no ranking operators");
    }
}

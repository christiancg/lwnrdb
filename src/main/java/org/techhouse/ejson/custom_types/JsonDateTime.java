package org.techhouse.ejson.custom_types;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;

public class JsonDateTime extends JsonCustom<LocalDateTime> {
    public static final String CUSTOM_TYPE_NAME = "datetime";

    public JsonDateTime(LocalDateTime customValue) {
        super(customValue);
    }

    public JsonDateTime(String strValue) {
        super(strValue);
    }

    public JsonDateTime() {
        super();
    }

    @Override
    public String getCustomTypeName() {
        return CUSTOM_TYPE_NAME;
    }

    @Override
    protected LocalDateTime parse() {
        try {
            return LocalDateTime.parse(stringDataValue());
        } catch (Exception e) {
            throw new WrongFormatCustomTypeException(getClass().getName(), e);
        }
    }

    @Override
    public Integer compare(LocalDateTime another) {
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

package org.techhouse.ops.req.agg.step;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public class ReduceAggregationStep extends BaseAggregationStep {
    public static final String DEFAULT_RESULT_FIELD = "value";

    private String script;
    private JsonBaseElement initialValue;
    private String resultField;

    public ReduceAggregationStep(String script, JsonBaseElement initialValue, String resultField) {
        super(AggregationStepType.REDUCE);
        this.script = script;
        this.initialValue = initialValue;
        this.resultField = resultField == null || resultField.isBlank() ? DEFAULT_RESULT_FIELD : resultField;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public JsonBaseElement getInitialValue() {
        return initialValue;
    }

    public void setInitialValue(JsonBaseElement initialValue) {
        this.initialValue = initialValue;
    }

    public String getResultField() {
        return resultField;
    }

    public void setResultField(String resultField) {
        this.resultField = resultField;
    }
}

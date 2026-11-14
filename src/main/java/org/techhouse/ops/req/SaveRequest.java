package org.techhouse.ops.req;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.agg.BaseOperator;

public class SaveRequest extends OperationRequest {
    public String _id;
    public JsonObject object;
    public BaseOperator condition;
    private transient boolean backgroundFollowOn;

    public SaveRequest(String databaseName, String collectionName) {
        super(OperationType.SAVE, databaseName, collectionName);
    }

    public String get_id() {
        return _id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }

    public JsonObject getObject() {
        return object;
    }

    public void setObject(JsonObject object) {
        this.object = object;
    }

    public BaseOperator getCondition() {
        return condition;
    }

    public void setCondition(BaseOperator condition) {
        this.condition = condition;
    }

    public boolean isBackgroundFollowOn() {
        return backgroundFollowOn;
    }

    public void setBackgroundFollowOn(boolean backgroundFollowOn) {
        this.backgroundFollowOn = backgroundFollowOn;
    }
}

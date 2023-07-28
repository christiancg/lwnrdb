package org.techhouse.ops.req;

import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class SaveRequest extends OperationRequest {
    public JsonObject object;
    public SaveRequest(String databaseName, String collectionName) {
        super(OperationType.SAVE, databaseName, collectionName);
    }
}

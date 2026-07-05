package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ListenResponse extends OperationResponse {
    public String listenId;
    public List<JsonObject> results;
    public String resultHash;
    public boolean isUpdate;

    public ListenResponse(String listenId, List<JsonObject> results, String resultHash, boolean isUpdate) {
        super(OperationType.LISTEN, OperationStatus.OK, isUpdate ? "Query results updated" : "Listening for changes");
        this.listenId = listenId;
        this.results = results;
        this.resultHash = resultHash;
        this.isUpdate = isUpdate;
    }

    public String getListenId() {
        return listenId;
    }

    public void setListenId(String listenId) {
        this.listenId = listenId;
    }

    public List<JsonObject> getResults() {
        return results;
    }

    public void setResults(List<JsonObject> results) {
        this.results = results;
    }

    public String getResultHash() {
        return resultHash;
    }

    public void setResultHash(String resultHash) {
        this.resultHash = resultHash;
    }

    public boolean isUpdate() {
        return isUpdate;
    }

    public void setUpdate(boolean isUpdate) {
        this.isUpdate = isUpdate;
    }
}

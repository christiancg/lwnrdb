package org.techhouse.ops.req;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;

public class TestTriggerRequest extends OperationRequest {
    private String name;
    private String event;
    private JsonObject document;

    public TestTriggerRequest() {
        super(OperationType.TEST_TRIGGER, null, null);
    }

    public TestTriggerRequest(String databaseName, String collectionName, String name, String event,
            JsonObject document) {
        super(OperationType.TEST_TRIGGER, databaseName, collectionName);
        this.name = name;
        this.event = event;
        this.document = document;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public JsonObject getDocument() {
        return document;
    }

    public void setDocument(JsonObject document) {
        this.document = document;
    }
}

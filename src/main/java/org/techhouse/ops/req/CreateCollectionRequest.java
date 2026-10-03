package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class CreateCollectionRequest extends OperationRequest {
    private long incarnation;
    private String incarnationText;

    public CreateCollectionRequest(String databaseName, String collectionName) {
        super(OperationType.CREATE_COLLECTION, databaseName, collectionName);
    }

    public long getIncarnation() {
        if (incarnationText == null || incarnationText.isBlank()) {
            return incarnation;
        }
        try {
            return Long.parseLong(incarnationText);
        } catch (NumberFormatException e) {
            return incarnation;
        }
    }

    public void setIncarnation(long incarnation) {
        this.incarnation = incarnation;
        this.incarnationText = Long.toString(incarnation);
    }
}

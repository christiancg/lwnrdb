package org.techhouse.ops.req;

import java.util.List;
import org.techhouse.ops.OperationType;

public class SaveTriggerRequest extends VersionedDefinitionRequest {
    private String name;
    private List<String> events;
    private String procedureName;
    private String mode;
    private String timing;
    private Boolean allowCascade;
    private String stampedDefiner;

    public SaveTriggerRequest() {
        super(OperationType.SAVE_TRIGGER, null, null);
    }

    public SaveTriggerRequest(String databaseName, String collectionName, String name, List<String> events,
            String procedureName) {
        super(OperationType.SAVE_TRIGGER, databaseName, collectionName);
        this.name = name;
        this.events = events;
        this.procedureName = procedureName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getEvents() {
        return events == null ? List.of() : events;
    }

    public void setEvents(List<String> events) {
        this.events = events;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public void setProcedureName(String procedureName) {
        this.procedureName = procedureName;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getTiming() {
        return timing;
    }

    public void setTiming(String timing) {
        this.timing = timing;
    }

    public boolean isAllowCascade() {
        return allowCascade != null && allowCascade;
    }

    public void setAllowCascade(Boolean allowCascade) {
        this.allowCascade = allowCascade;
    }

    public String getStampedDefiner() {
        return stampedDefiner;
    }

    public void setStampedDefiner(String stampedDefiner) {
        this.stampedDefiner = stampedDefiner;
    }
}

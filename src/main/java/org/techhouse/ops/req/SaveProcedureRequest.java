package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class SaveProcedureRequest extends VersionedDefinitionRequest {
    private String name;
    private String script;
    private String description;

    public SaveProcedureRequest() {
        super(OperationType.SAVE_PROCEDURE, null, null);
    }

    public SaveProcedureRequest(String databaseName, String name, String script) {
        super(OperationType.SAVE_PROCEDURE, databaseName, null);
        this.name = name;
        this.script = script;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

}

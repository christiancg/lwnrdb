package org.techhouse.cluster.msg;

public class RunningScript {
    private String runId;
    private String kind;
    private String database;
    private String name;
    private String username;
    private long startedAt;

    @SuppressWarnings("unused")
    public RunningScript() {
    }

    public RunningScript(String runId, String kind, String database, String name, String username, long startedAt) {
        this.runId = runId;
        this.kind = kind;
        this.database = database;
        this.name = name;
        this.username = username;
        this.startedAt = startedAt;
    }

    public String getRunId() {
        return runId;
    }

    public String getKind() {
        return kind;
    }

    public String getDatabase() {
        return database;
    }

    public String getName() {
        return name;
    }

    public String getUsername() {
        return username;
    }

    public long getStartedAt() {
        return startedAt;
    }
}

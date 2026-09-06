package org.techhouse.cluster.msg;

/**
 * One script run executing on a node, as reported over LIST_SCRIPTS(_ACK): its run id, what kind of run it
 * is, the database it is scoped to, the procedure/trigger/schedule name (absent for an ad-hoc RUN_SCRIPT),
 * the user whose authority it runs with, and when it started.
 */
public class RunningScript {
    private String runId;
    private String kind;
    private String database;
    private String name;
    private String username;
    private long startedAt;

    // EJson's ReflectionUtils.createInstance looks for a public no-arg constructor before anything
    // else, so this is what deserializes a LIST_SCRIPTS_ACK; without it the wire class would fall
    // through to UnsafeAllocator. Called only reflectively, hence the suppression.
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

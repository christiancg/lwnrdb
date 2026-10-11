package org.techhouse.test;

public class TestGlobals {
    public static final long APPLY_BUDGET_MS = 10_000L;
    public static final String DB = "myDb";
    public static final String COLL = "myCollection";
    public static final String JOIN_COLL = "joinCollection";
    public static final String PATH = System.getProperty("lwnrdb.test.path", "test");
    public static final String LOG_PATH = System.getProperty("lwnrdb.test.logPath", "testLog");
}

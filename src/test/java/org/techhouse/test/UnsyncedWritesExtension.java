package org.techhouse.test;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.techhouse.config.Configuration;

@NullMarked
public class UnsyncedWritesExtension implements BeforeAllCallback, BeforeEachCallback {
    private static final String UNSYNCED = "false";

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        unsync();
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        unsync();
    }

    private static void unsync() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "syncWrites", UNSYNCED);
    }
}

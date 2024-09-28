package org.techhouse.test;

import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;

import java.io.File;
import java.io.IOException;

public class TestUtils {
    public static void standardInitialSetup() throws NoSuchFieldException, IllegalAccessException, IOException {
        final var config = Configuration.getInstance();
        final var field = Configuration.class.getDeclaredField("filePath");
        field.setAccessible(true);
        field.set(config, TestGlobals.PATH);
        final var fs = IocContainer.get(FileSystem.class);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        final var cache = IocContainer.get(Cache.class);
        cache.loadAdminData();
    }

    public static void standardTearDown() throws IOException, InterruptedException {
        AdminOperationHelper.deleteDatabaseEntry(TestGlobals.DB);
        deleteDir(new File(TestGlobals.PATH));
    }

    private static void deleteDir(File file) throws IOException {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDir(f);
            }
        }
        if (!file.delete()) {
            throw new IOException("File " + file.getName() + " was not deleted");
        }
    }

    public static void createTestDatabaseAndCollection() throws IOException, InterruptedException {
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(TestGlobals.DB));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL));
    }
}

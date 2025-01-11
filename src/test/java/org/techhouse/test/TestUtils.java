package org.techhouse.test;

import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.utils.ReflectionUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

    public static void standardTearDown() throws IOException, NoSuchFieldException, IllegalAccessException {
        deleteDir(new File(TestGlobals.PATH));
        clearCache();
    }

    private static void clearCache() throws NoSuchFieldException, IllegalAccessException {
        Cache cache = IocContainer.get(Cache.class);
        TestUtils.setPrivateField( cache, "collections", new ConcurrentHashMap<>());
        TestUtils.setPrivateField( cache, "databases", new ConcurrentHashMap<>());
        TestUtils.setPrivateField( cache, "databasesPkIndex", new ConcurrentHashMap<>());
        TestUtils.setPrivateField( cache, "collectionsPkIndex", new ConcurrentHashMap<>());
        TestUtils.setPrivateField( cache, "collectionMap", new ConcurrentHashMap<>());
        TestUtils.setPrivateField( cache, "fieldIndexMap", new ConcurrentHashMap<>());
        TestUtils.setPrivateField( cache, "pkIndexMap", new ConcurrentHashMap<>());
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
        FileSystem fs = IocContainer.get(FileSystem.class);
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
    }

    public static <U, T> T getPrivateField(U object, String fieldName, ReflectionUtils.TypeToken<T> fieldType) throws NoSuchFieldException, IllegalAccessException {
        final var field = object.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        final var tClass = fieldType.getTypeParameter();
        return tClass.cast(field.get(object));
    }

    public static <U, T> T getPrivateField(U object, String fieldName, Class<T> fieldType) throws NoSuchFieldException, IllegalAccessException {
        final var field = object.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return fieldType.cast(field.get(object));
    }

    public static <U, T> void setPrivateField(U object, String fieldName, T fieldValue) throws NoSuchFieldException, IllegalAccessException {
        final var field = object.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(object, fieldValue);
    }

    public static <U> Method getPrivateMethod(U object, String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        final var method = object.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    public static void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if(files!=null) { //some JVMs return null for empty dirs
            for(File f: files) {
                if(f.isDirectory()) {
                    deleteFolder(f);
                } else {
                    assertTrue(f.delete());
                }
            }
        }
        assertTrue(folder.delete());
    }

    public static void releaseAllLocks() throws NoSuchFieldException, IllegalAccessException {
        final var locker = IocContainer.get(ResourceLocking.class);
        final var locksType = new ReflectionUtils.TypeToken<Map<String, Semaphore>>() {};
        final var locks = TestUtils.getPrivateField(locker, "locks", locksType);
        for (var lock : locks.entrySet()) {
            lock.getValue().release();
        }
    }
}

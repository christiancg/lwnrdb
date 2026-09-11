package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.UserCache;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class UserCacheCollectionTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Handles the case where the collection is not in the cache
    @Test
    public void handles_collection_not_in_cache() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        // Arrange
        String dbName = TestGlobals.DB;
        String collName = TestGlobals.COLL;
        PkIndexEntry idxEntry = new PkIndexEntry(dbName, collName, "testValue", 0, 100, 0);
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = UserCache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        DbEntry expectedEntry = new DbEntry();
        expectedEntry.setDatabaseName(dbName);
        expectedEntry.setCollectionName(collName);
        expectedEntry.set_id("testValue");

        when(fsMock.getById(idxEntry)).thenReturn(expectedEntry);

        // Act
        DbEntry result = cache.getById(dbName, collName, idxEntry);

        // Assert
        assertEquals(expectedEntry, result);
    }
}

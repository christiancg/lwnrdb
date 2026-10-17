package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ex.InvalidPathSegmentException;

public class FilePathsContainmentTest {
    private static final String ROOT = "root";
    private final FilePaths paths = new FilePaths();

    @BeforeEach
    public void setUp() {
        paths.useDbPath(ROOT);
    }

    private List<Function<String, File>> everyBuilder() {
        return List.of(paths::databaseFolder, paths::rawDatabaseFolder, name -> paths.collectionFolder("shop", name),
                name -> paths.collectionFolder(name, "orders"), name -> paths.collectionPage(name, "orders", 1),
                name -> paths.collectionPage("shop", name, 1),
                name -> paths.indexFile("shop", "orders", name, "String"),
                name -> paths.indexFile(name, "orders", "field", "String"), name -> paths.procedureFile("shop", name),
                name -> paths.procedureFile(name, "nightly"), name -> paths.scheduleFile("shop", name),
                name -> paths.scheduleFile(name, "nightly"), name -> paths.schemaFile("shop", name),
                name -> paths.schemaFile(name, "orders"), name -> paths.triggersFile("shop", name),
                name -> paths.triggersFile(name, "orders"), name -> paths.pkIndexFile(name, "orders"),
                name -> paths.tombstoneFile("shop", name));
    }

    @Test
    public void rejectsDotDotAsDatabase() {
        assertThrows(InvalidPathSegmentException.class, () -> paths.databaseFolder(".."));
        assertThrows(InvalidPathSegmentException.class, () -> paths.rawDatabaseFolder(".."));
    }

    @Test
    public void rejectsDotAsCollection() {
        assertThrows(InvalidPathSegmentException.class, () -> paths.collectionFolder("shop", "."));
    }

    @Test
    public void rejectsSeparatorsInEveryBuilder() {
        for (final var builder : everyBuilder()) {
            for (final var bad : List.of("a/b", "a\\b", "../x", "")) {
                assertThrows(InvalidPathSegmentException.class, () -> builder.apply(bad), bad);
            }
        }
    }

    @Test
    public void rejectsNul() {
        assertThrows(InvalidPathSegmentException.class, () -> paths.databaseFolder("a\0b"));
        assertThrows(InvalidPathSegmentException.class, () -> paths.indexFile("shop", "orders", "a\0b", "String"));
    }

    @Test
    public void rejectsNull() {
        assertThrows(InvalidPathSegmentException.class, () -> paths.databaseFolder(null));
        assertThrows(InvalidPathSegmentException.class, () -> paths.procedureFile("shop", null));
    }

    @Test
    public void acceptsAdminPagesAndTranslatesIt() {
        assertEquals(new File(ROOT + Globals.FILE_SEPARATOR + Globals.ADMIN_DB_NAME + Globals.FILE_SEPARATOR
                + Globals.ADMIN_PAGES_FOLDER), paths.databaseFolder(Globals.ADMIN_PAGES_DB_NAME));
    }

    @Test
    public void acceptsOrdinaryNames() {
        for (final var builder : everyBuilder()) {
            assertNotNull(builder.apply("orders_1"));
        }
        assertNotNull(paths.indexFile("shop", "orders", "a.b", "String"));
        assertEquals(new File(ROOT + Globals.FILE_SEPARATOR + "shop"), paths.databaseFolder("shop"));
    }

    @Test
    public void indexBuildingMarkersRefuseASeparatorBearingFieldName() {
        final var markers = new IndexMarkers(paths);
        for (final var bad : List.of("../escape", "a/b", "a\\b", "a\0b", "")) {
            assertThrows(InvalidPathSegmentException.class, () -> markers.mark("shop", "orders", bad), bad);
        }
    }
}

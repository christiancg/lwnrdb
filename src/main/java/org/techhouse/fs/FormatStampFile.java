package org.techhouse.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public final class FormatStampFile {
    private static final String FORMAT_VERSION = "formatVersion";
    private static final String WRITTEN_BY = "writtenBy";
    private final EJson eJson = IocContainer.get(EJson.class);
    private final FilePaths paths;

    FormatStampFile(FilePaths paths) {
        this.paths = paths;
    }

    public Path path() {
        return paths.formatStampFile().toPath();
    }

    public FormatStamp read() throws IOException {
        final var path = path();
        if (!Files.exists(path)) {
            return null;
        }
        final var stamp = parse(path, Files.readString(path, StandardCharsets.UTF_8));
        return new FormatStamp(formatVersionOf(path, stamp), writtenByOf(path, stamp));
    }

    public void write(FormatStamp stamp) throws IOException {
        final var json = new JsonObject();
        json.addProperty(FORMAT_VERSION, stamp.formatVersion());
        json.addProperty(WRITTEN_BY, stamp.writtenBy());
        FileLocks.rewriteFileAtomically(path(), List.of(eJson.toJson(json)));
    }

    public boolean holdsData() {
        return paths.databaseFolder(Globals.ADMIN_DB_NAME).isDirectory();
    }

    private JsonObject parse(Path path, String content) throws MalformedFormatStampException {
        final JsonObject stamp;
        try {
            stamp = eJson.fromJson(content.strip(), JsonObject.class);
        } catch (RuntimeException e) {
            throw new MalformedFormatStampException(path, "it is not a JSON object (" + e.getMessage() + ")");
        }
        if (stamp == null) {
            throw new MalformedFormatStampException(path, "it is not a JSON object");
        }
        return stamp;
    }

    private static int formatVersionOf(Path path, JsonObject stamp) throws MalformedFormatStampException {
        final var value = stamp.get(FORMAT_VERSION);
        if (value == null || !value.isJsonNumber()) {
            throw new MalformedFormatStampException(path, FORMAT_VERSION + " is missing or not a number");
        }
        final var version = value.asJsonNumber().doubleValue();
        if (version < 1 || version > Integer.MAX_VALUE || version != Math.rint(version)) {
            throw new MalformedFormatStampException(path, FORMAT_VERSION + " must be a positive whole number");
        }
        return (int) version;
    }

    private static String writtenByOf(Path path, JsonObject stamp) throws MalformedFormatStampException {
        final var value = stamp.get(WRITTEN_BY);
        if (value == null || !value.isJsonString()) {
            throw new MalformedFormatStampException(path, WRITTEN_BY + " is missing or not a string");
        }
        return value.asJsonString().getValue();
    }
}

package org.techhouse.log;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.fs.io.AppendingFile;
import org.techhouse.fs.io.LogFolder;
import org.techhouse.fs.io.OwnerOnlyFiles;

public class LogWriter {
    private static final Configuration config = Configuration.getInstance();
    private static final ReentrantLock WRITER_LOCK = new ReentrantLock();
    private static final String ROLLED_SUFFIX = ".1";
    private static AppendingFile openWriter;
    private static String openPath;
    private static boolean needsLeadingNewline;
    private static long openBytes;

    public static void createLogPathAndRemoveOldFiles() throws IOException {
        final var logDirectory = Path.of(config.getLogPath());
        if (!createOwnerOnly(logDirectory)) {
            System.out.println("Error creating log directory");
            return;
        }
        deleteOldLogFiles(logDirectory);
        createCurrentLogFileIfNecessary();
    }

    private static boolean createOwnerOnly(Path logDirectory) {
        try {
            OwnerOnlyFiles.createDirectories(logDirectory);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void createCurrentLogFileIfNecessary() throws IOException {
        if (!LogFolder.createFileIfAbsent(currentLogFile())) {
            System.out.println("Error creating log file");
        }
    }

    public static void deleteOldLogFiles(Path logDirectory) {
        final List<Path> allFiles;
        try {
            allFiles = LogFolder.listRegularFiles(logDirectory);
        } catch (IOException e) {
            return;
        }
        final var maxLogFiles = config.getMaxLogFiles();
        final var now = LocalDate.now();
        final var fileNames = new ArrayList<String>();
        fileNames.add(logFileName(LocalDate.now()));
        for (var i = 1; i <= maxLogFiles; i++) {
            fileNames.add(logFileName(now.minusDays(i)));
        }
        fileNames.addAll(fileNames.stream().map(LogWriter::rolledName).toList());
        final var toDelete = allFiles.stream().filter(LogWriter::isDatedLogFile)
                .filter(file -> !fileNames.contains(nameOf(file))).toList();
        for (var fileToDelete : toDelete) {
            if (!LogFolder.delete(fileToDelete)) {
                System.out.println("Error deleting old log file with name " + nameOf(fileToDelete));
            }
        }
    }

    private static String nameOf(Path file) {
        return Objects.toString(file.getFileName(), "");
    }

    private static boolean isDatedLogFile(Path file) {
        final var name = nameOf(file);
        if (!name.endsWith(Globals.LOG_FILE_EXTENSION)) {
            return false;
        }
        var stem = name.substring(0, name.length() - Globals.LOG_FILE_EXTENSION.length());
        if (stem.endsWith(ROLLED_SUFFIX)) {
            stem = stem.substring(0, stem.length() - ROLLED_SUFFIX.length());
        }
        try {
            LocalDate.parse(stem, DateTimeFormatter.ISO_DATE);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Path currentLogFile() {
        return Path.of(config.getLogPath(), logFileName(LocalDate.now()));
    }

    private static String logFileName(LocalDate date) {
        return date.format(DateTimeFormatter.ISO_DATE) + Globals.LOG_FILE_EXTENSION;
    }

    private static String rolledName(String logFileName) {
        return logFileName.substring(0, logFileName.length() - Globals.LOG_FILE_EXTENSION.length()) + ROLLED_SUFFIX
                + Globals.LOG_FILE_EXTENSION;
    }

    public static void writeLogEntry(String logEntry) {
        try {
            appendToLogFile(logEntry);
            System.out.println(logEntry);
        } catch (Exception e) {
            System.out.println("Warning: could not write log entry -> " + logEntry);
        }
    }

    private static void appendToLogFile(String logEntry) throws IOException {
        WRITER_LOCK.lock();
        try {
            final var file = currentLogFile();
            final var path = file.toAbsolutePath().toString();
            ensureWriterFor(file, path);
            if (openBytes > 0 && openBytes + logEntry.length() > config.sizeValue(ConfigKey.MAX_LOG_FILE_SIZE)) {
                roll(file);
                ensureWriterFor(file, path);
            }
            if (needsLeadingNewline) {
                openWriter.append(Globals.NEWLINE);
                openBytes += Globals.NEWLINE.length();
            }
            openWriter.append(logEntry);
            openWriter.flush();
            openBytes += logEntry.length();
            needsLeadingNewline = true;
        } finally {
            WRITER_LOCK.unlock();
        }
    }

    private static void roll(Path file) throws IOException {
        closeQuietly();
        LogFolder.move(file, file.resolveSibling(rolledName(nameOf(file))));
    }

    public static void flushAndClose() {
        WRITER_LOCK.lock();
        try {
            closeQuietly();
        } finally {
            WRITER_LOCK.unlock();
        }
    }

    private static boolean isWriterCurrent(String path) {
        return openWriter != null && path.equals(openPath) && openWriter.isPresent();
    }

    private static void ensureWriterFor(Path file, String path) throws IOException {
        if (isWriterCurrent(path)) {
            return;
        }
        closeQuietly();
        final var writer = AppendingFile.open(file, Globals.BUFFER_SIZE);
        openBytes = writer.length();
        needsLeadingNewline = openBytes > 0;
        openPath = path;
        openWriter = writer;
    }

    private static void closeQuietly() {
        final var writer = openWriter;
        openWriter = null;
        openPath = null;
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            System.out.println("Warning: could not close the log file -> " + e.getMessage());
        }
    }
}

package org.techhouse.log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.utils.OwnerOnlyPermissions;

public class LogWriter {
    private static final Configuration config = Configuration.getInstance();
    private static final ReentrantLock WRITER_LOCK = new ReentrantLock();
    private static final String ROLLED_SUFFIX = ".1";
    private static BufferedWriter openWriter;
    private static String openPath;
    private static boolean needsLeadingNewline;
    private static long openBytes;

    public static void createLogPathAndRemoveOldFiles() throws IOException {
        final var logPath = config.getLogPath();
        final var logDirectory = new File(logPath);
        if (!logDirectory.exists()) {
            if (createOwnerOnly(logDirectory)) {
                createCurrentLogFileIfNecessary();
            } else {
                System.out.println("Error creating log directory");
            }
        } else {
            deleteOldLogFiles(logDirectory);
            createCurrentLogFileIfNecessary();
        }
    }

    private static boolean createOwnerOnly(File logDirectory) {
        try {
            OwnerOnlyPermissions.createDirectories(logDirectory.toPath());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void createCurrentLogFileIfNecessary() throws IOException {
        final var currentLogFile = currentLogFile();
        if (!currentLogFile.exists()) {
            OwnerOnlyPermissions.ensureOwnerOnlyFileQuietly(currentLogFile.toPath());
            if (!currentLogFile.exists() && !currentLogFile.createNewFile()) {
                System.out.println("Error creating log file");
            }
        }
    }

    public static void deleteOldLogFiles(File logDirectory) {
        final var maxLogFiles = config.getMaxLogFiles();
        final var allFiles = logDirectory.listFiles();
        if (allFiles != null) {
            final var now = LocalDate.now();
            final var fileNames = new ArrayList<String>();
            fileNames.add(logFileName(LocalDate.now()));
            for (var i = 1; i <= maxLogFiles; i++) {
                fileNames.add(logFileName(now.minusDays(i)));
            }
            fileNames.addAll(fileNames.stream().map(LogWriter::rolledName).toList());
            final var toDelete = Arrays.stream(allFiles).filter(LogWriter::isDatedLogFile)
                    .filter(file -> !fileNames.contains(file.getName())).toList();
            for (var fileToDelete : toDelete) {
                if (!fileToDelete.delete()) {
                    System.out.println("Error deleting old log file with name " + fileToDelete.getName());
                }
            }
        }
    }

    private static boolean isDatedLogFile(File file) {
        final var name = file.getName();
        if (!file.isFile() || !name.endsWith(Globals.LOG_FILE_EXTENSION)) {
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

    private static File currentLogFile() {
        return new File(config.getLogPath() + Globals.FILE_SEPARATOR + logFileName(LocalDate.now()));
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
            ensureWriterFor(file, file.getAbsolutePath());
            if (openBytes > 0 && openBytes + logEntry.length() > config.sizeValue(ConfigKey.MAX_LOG_FILE_SIZE)) {
                roll(file);
                ensureWriterFor(file, file.getAbsolutePath());
            }
            if (needsLeadingNewline) {
                openWriter.write(Globals.NEWLINE);
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

    private static void roll(File file) throws IOException {
        closeQuietly();
        final var rolled = file.toPath().resolveSibling(rolledName(file.getName()));
        Files.move(file.toPath(), rolled, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void flushAndClose() {
        WRITER_LOCK.lock();
        try {
            closeQuietly();
        } finally {
            WRITER_LOCK.unlock();
        }
    }

    private static boolean isWriterCurrent(File file, String path) {
        return openWriter != null && path.equals(openPath) && file.exists();
    }

    private static void ensureWriterFor(File file, String path) throws IOException {
        if (isWriterCurrent(file, path)) {
            return;
        }
        closeQuietly();
        needsLeadingNewline = file.exists() && file.length() > 0;
        openBytes = file.exists() ? file.length() : 0L;
        openPath = path;
        OwnerOnlyPermissions.ensureOwnerOnlyFileQuietly(file.toPath());
        openWriter = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8, true), Globals.BUFFER_SIZE);
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

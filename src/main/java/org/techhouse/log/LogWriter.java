package org.techhouse.log;

import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;

public class LogWriter {
    private static final Configuration config = Configuration.getInstance();

    public static void createLogPathAndRemoveOldFiles()
            throws IOException {
        final var logPath = config.getLogPath();
        final var logDirectory = new File(logPath);
        if (!logDirectory.exists()) {
            if (!logDirectory.mkdir()) {
                System.out.println("Error creating log directory");
            } else {
                createCurrentLogFileIfNecessary();
            }
        } else {
            deleteOldLogFiles(logDirectory);
            createCurrentLogFileIfNecessary();
        }
    }

    private static void createCurrentLogFileIfNecessary()
            throws IOException {
        final var currentLogFile = currentLogFile();
        if (!currentLogFile.exists()) {
            if (!currentLogFile.createNewFile()) {
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
            for(var i = 1; i <= maxLogFiles; i++) {
                fileNames.add(logFileName(now.minusDays(i)));
            }
            final var toDelete = Arrays.stream(allFiles).filter(file -> !fileNames.contains(file.getName())).toList();
            for (var fileToDelete : toDelete) {
                if (!fileToDelete.delete()) {
                    System.out.println("Error deleting old log file with name " + fileToDelete.getName());
                }
            }
        }
    }

    private static File currentLogFile() {
        return new File(config.getLogPath() + Globals.FILE_SEPARATOR + logFileName(LocalDate.now()));
    }

    private static String logFileName(LocalDate date) {
        return date.format(DateTimeFormatter.ISO_DATE) + Globals.LOG_FILE_EXTENSION;
    }

    public static void writeLogEntry(String logEntry) {
        final var file = currentLogFile();
        try (final var writer = new BufferedWriter(new FileWriter(file, true), Globals.BUFFER_SIZE)) {
            if (file.length() > 0) {
                writer.write(Globals.NEWLINE);
            }
            writer.append(logEntry);
            System.out.println(logEntry);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

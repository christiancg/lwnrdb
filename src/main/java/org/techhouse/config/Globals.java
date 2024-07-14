package org.techhouse.config;

import java.nio.file.FileSystems;

public class Globals {
    public static final String PK_FIELD = "_id";
    public static final String PK_FIELD_TYPE = "String";
    public static final int BUFFER_SIZE = 32768;
    public static final String DB_FILE_EXTENSION = ".dat";
    public static final String INDEX_FILE_EXTENSION = ".idx";
    public static final String RW_PERMISSIONS = "rwd";
    public static final String R_PERMISSIONS = "r";
    public static final char COLL_IDENTIFIER_SEPARATOR = '|';
    public static final String COLL_IDENTIFIER_SEPARATOR_REGEX = "\\|";
    public static final char INDEX_FILE_NAME_SEPARATOR = '-';
    public static final String INDEX_ENTRY_SEPARATOR = "<|>";
    public static final String INDEX_ENTRY_SEPARATOR_REGEX = "<\\|>";
    public static final String STRING_LITERAL_PREFIX = "-";
    public static final String FILE_CONFIG_NAME = "lwnrdb.cfg";
    public static final String ADMIN_DB_NAME = "admin";
    public static final String ADMIN_DATABASES_COLLECTION_NAME = "databases";
    public static final String ADMIN_COLLECTIONS_COLLECTION_NAME = "collections";
    public static final String LOG_FILE_EXTENSION = ".log";
    public static final String FILE_SEPARATOR = FileSystems.getDefault().getSeparator();
    public static final String NEWLINE = System.lineSeparator();
    public static final String NEWLINE_REGEX = System.lineSeparator().equals("\n") ? "\\n" : "\\r\\n";
    public static final int NEWLINE_CHAR_LENGTH = System.lineSeparator().equals("\n") ? 1 : 2;
    public static final String CLOSE_CONNECTION_MESSAGE = "Bye!";
    public static final String READ_WHOLE_COLLECTION_REGEX = "(?=(?<!:)\\{)";
    public static final String CUSTOM_JSON_REGEX = "^#[a-zA-Z0-9]{3,20}\\(.*\\)$";
}

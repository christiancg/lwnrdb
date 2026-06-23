package org.techhouse.config;

import java.nio.file.FileSystems;

public final class Globals {
    private Globals() {
    }
    public static final String PK_FIELD = "_id";
    public static final String PK_FIELD_TYPE = "String";
    public static final String INDEX_TYPE_NUMBER = "Number";
    public static final String INDEX_TYPE_STRING = "String";
    public static final String INDEX_TYPE_BOOLEAN = "Boolean";
    public static final String INDEX_TYPE_NULL = "JsonNull";
    public static final String INDEX_TYPE_OBJECT = "Object";
    public static final String INDEX_TYPE_ARRAY = "Array";
    public static final int BUFFER_SIZE = 32768;
    public static final String DB_FILE_EXTENSION = ".dat";
    public static final String INDEX_FILE_EXTENSION = ".idx";
    public static final String RW_PERMISSIONS = "rwd";
    public static final String R_PERMISSIONS = "r";
    public static final char COLL_IDENTIFIER_SEPARATOR = '|';
    public static final String COLL_IDENTIFIER_SEPARATOR_REGEX = "\\|";
    public static final char INDEX_FILE_NAME_SEPARATOR = '-';
    public static final String INDEX_ENTRY_SEPARATOR = "|";
    public static final String ID_SEPARATOR = "";
    public static final String STRING_LITERAL_PREFIX = "-";
    public static final String FILE_CONFIG_NAME = "lwnrdb.cfg";
    public static final String ADMIN_DB_NAME = "admin";
    public static final String ADMIN_DATABASES_COLLECTION_NAME = "databases";
    public static final String ADMIN_COLLECTIONS_COLLECTION_NAME = "collections";
    public static final String ADMIN_USERS_COLLECTION_NAME = "users";
    public static final String ADMIN_PAGES_PER_COLLECTION_NAME = "pages_%s_%s";
    public static final String ADMIN_COLLECTION_USAGE_NAME = "collection_usage";
    public static final long CACHE_DISABLED = -1L;
    public static final long CACHE_UNLIMITED = 0L;
    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final String DEFAULT_ADMIN_PASSWORD = "administrator";
    public static final String LOG_FILE_EXTENSION = ".log";
    public static final String FILE_SEPARATOR = FileSystems.getDefault().getSeparator();
    public static final String FILE_PAGE_SEPARATOR = "-";
    public static final String NEWLINE = System.lineSeparator();
    public static final String NEWLINE_REGEX = System.lineSeparator().equals("\n") ? "\\n" : "\\r\\n";
    public static final int NEWLINE_CHAR_LENGTH = System.lineSeparator().equals("\n") ? 1 : 2;
    public static final String CLOSE_CONNECTION_MESSAGE = "Bye!";
    public static final String CUSTOM_JSON_REGEX = "^#[a-zA-Z0-9]{3,20}\\(.*\\)$";
    public static final String TLS_KEY_ALIAS = "lwnrdb";
    public static final String TLS_KEY_ALGORITHM = "RSA";
    public static final int TLS_KEY_SIZE = 2048;
    public static final String TLS_SIGNATURE_ALGORITHM = "SHA256withRSA";
    public static final int TLS_CERT_VALIDITY_DAYS = 365;
    public static final String TLS_CERT_DNAME = "lwnrdb";
    public static final String TLS_KEYSTORE_TYPE = "PKCS12";
    public static final String TLS_PROTOCOL = "TLS";
}

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
    // Per-collection JSON Schema file: {coll}-schema.json holds the single validation schema for the
    // collection (user data, stored in the collection folder). Absent = the collection is unconstrained.
    public static final String SCHEMA_FILE_NAME = "schema";
    public static final String SCHEMA_FILE_EXTENSION = ".json";
    // Per-database stored procedures live in a folder beside the database's collection folders. The
    // leading '.' cannot appear in a collection name, so the folder can never collide with one.
    public static final String PROCEDURES_FOLDER = ".procedures";
    public static final String PROCEDURE_FILE_EXTENSION = ".json";
    // Per-database schedules live beside the procedures folder, and cannot collide with a collection name
    // for the same reason: a leading '.' is unrepresentable in one.
    public static final String SCHEDULES_FOLDER = ".schedules";
    public static final String SCHEDULE_FILE_EXTENSION = ".json";
    // Per-collection trigger file infix: {coll}-triggers.json holds every trigger on the collection,
    // stored in the collection folder beside its schema so a DROP_COLLECTION removes it with the data.
    public static final String TRIGGERS_FILE_NAME = "triggers";
    public static final String TRIGGERS_FILE_EXTENSION = ".json";
    public static final String RW_PERMISSIONS = "rwd";
    public static final String R_PERMISSIONS = "r";
    public static final char COLL_IDENTIFIER_SEPARATOR = '|';
    public static final String COLL_IDENTIFIER_SEPARATOR_REGEX = "\\|";
    public static final char INDEX_FILE_NAME_SEPARATOR = '-';
    public static final String INDEX_ENTRY_SEPARATOR = "|";
    // Per-collection tombstone file infix: {coll}-tombstones.idx holds id|version records of deleted
    // documents, so cluster anti-entropy can converge deletes (last-write-wins) without resurrecting them.
    public static final String TOMBSTONE_FILE_NAME = "tombstones";
    public static final String ID_SEPARATOR = "";
    public static final String STRING_LITERAL_PREFIX = "-";
    public static final String FILE_CONFIG_NAME = "lwnrdb.cfg";
    public static final String ADMIN_DB_NAME = "admin";
    public static final String ADMIN_DATABASES_COLLECTION_NAME = "databases";
    public static final String ADMIN_COLLECTIONS_COLLECTION_NAME = "collections";
    public static final String ADMIN_USERS_COLLECTION_NAME = "users";
    public static final String ADMIN_PAGES_FOLDER = "pages";
    public static final String ADMIN_PAGES_DB_NAME = "admin_pages";
    public static final String ADMIN_PAGES_PER_COLLECTION_NAME = "%s_%s";
    public static final String ADMIN_COLLECTION_USAGE_NAME = "collection_usage";
    public static final String ADMIN_TRANSACTIONS_COLLECTION_NAME = "transactions";
    public static final String ADMIN_TRIGGER_RUNS_COLLECTION_NAME = "trigger_runs";
    // A reserved collection in every user database, holding the history of the script runs that
    // touched it. Reserved so a client cannot write one by hand and a trigger cannot fire on it.
    public static final String SCRIPT_RUNS_COLLECTION_NAME = "script_runs";
    public static final long CACHE_DISABLED = -1L;
    public static final long CACHE_UNLIMITED = 0L;
    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final String DEFAULT_ADMIN_PASSWORD = "administrator";
    public static final String LOG_FILE_EXTENSION = ".log";
    public static final String FILE_SEPARATOR = FileSystems.getDefault().getSeparator();
    public static final String FILE_PAGE_SEPARATOR = "-";
    public static final String NEWLINE = System.lineSeparator();
    public static final int NEWLINE_CHAR_LENGTH = System.lineSeparator().equals("\n") ? 1 : 2;
    public static final String CLOSE_CONNECTION_MESSAGE = "Bye!";
    public static final String CUSTOM_JSON_REGEX = "^#[a-zA-Z0-9]{3,20}\\(.*\\)$";
    public static final double EARTH_RADIUS_METERS = 6371000.0;
    // Geohash-backed spatial acceleration: the finest precision considered when covering a query
    // bounding box, and the cap on how many geohash cells a covering may use before a coarser
    // precision is chosen (fewer, larger cells) to keep the range scan bounded.
    public static final int GEO_HASH_MAX_PRECISION = 9;
    public static final int GEO_HASH_MAX_COVERING_CELLS = 32;
    public static final String TLS_KEY_ALIAS = "lwnrdb";
    public static final String TLS_KEY_ALGORITHM = "RSA";
    public static final int TLS_KEY_SIZE = 2048;
    public static final String TLS_SIGNATURE_ALGORITHM = "SHA256withRSA";
    public static final int TLS_CERT_VALIDITY_DAYS = 365;
    public static final String TLS_CERT_DNAME = "lwnrdb";
    public static final String TLS_KEYSTORE_TYPE = "PKCS12";
    public static final String TLS_PROTOCOL = "TLS";
    public static final String CLUSTER_FOLDER = "cluster";
    public static final String CLUSTER_NODE_ID_FILE = "node.id";
    public static final String CLUSTER_ADMIN_EPOCH_FILE = "admin.epoch";
    public static final String CLUSTER_SEED_SEPARATOR = ",";
    public static final String CLUSTER_ADDRESS_SEPARATOR = ":";
    // Reserved ring key whose owner is the cluster's admin coordinator (serializes admin/DDL mutations).
    public static final String CLUSTER_ADMIN_COORDINATOR_KEY = "__admin_coordinator__";
}

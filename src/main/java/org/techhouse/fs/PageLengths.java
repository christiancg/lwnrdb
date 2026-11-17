package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongUnaryOperator;
import org.techhouse.config.Globals;

final class PageLengths {
    static final long UNKNOWN = -1L;
    static final int MAX_TRACKED_PAGE = 1 << 20;
    private static final Map<String, Pages> byCollection = new ConcurrentHashMap<>();

    private PageLengths() {
    }

    private record Address(String collection, long page) {
    }

    static final class CollectionPages implements LongUnaryOperator {
        private final FilePaths paths;
        private final String dbName;
        private final String collName;
        private final Pages pages;

        private CollectionPages(FilePaths paths, String dbName, String collName, Pages pages) {
            this.paths = paths;
            this.dbName = dbName;
            this.collName = collName;
            this.pages = pages;
        }

        @Override
        public long applyAsLong(long page) {
            if (page > MAX_TRACKED_PAGE) {
                return Math.max(statted(paths.collectionPage(dbName, collName, page)), 0L);
            }
            final var known = pages.known(page);
            return known == UNKNOWN ? pages.seed(page, paths.collectionPage(dbName, collName, page)) : lengthIn(known);
        }
    }

    static CollectionPages of(FilePaths paths, String dbName, String collName) {
        final var address = Objects.requireNonNull(addressOf(paths.collectionPage(dbName, collName, 0L)));
        return new CollectionPages(paths, dbName, collName, pagesOf(address.collection()));
    }

    static long lengthOf(File page) {
        final var address = addressOf(page);
        if (address == null) {
            return Math.max(statted(page), 0L);
        }
        final var pages = pagesOf(address.collection());
        final var known = pages.known(address.page());
        return known == UNKNOWN ? pages.seed(address.page(), page) : lengthIn(known);
    }

    static long knownLength(File page) {
        final var known = knownState(page);
        return known == UNKNOWN ? UNKNOWN : lengthIn(known);
    }

    static long terminatedLength(File page) {
        final var known = knownState(page);
        return known == UNKNOWN || (known & 1L) == 0L ? UNKNOWN : lengthIn(known);
    }

    static void grew(File page, long newLength, boolean endsWithLineEnd) {
        final var address = addressOf(page);
        if (address != null) {
            pagesOf(address.collection()).set(address.page(), newLength, endsWithLineEnd);
        }
    }

    static void cut(File page, long newLength) {
        grew(page, newLength, false);
    }

    static void forget(File file) {
        final var address = addressOf(file);
        if (address != null) {
            final var pages = byCollection.get(address.collection());
            if (pages != null) {
                pages.forget(address.page());
            }
        }
    }

    static void forgetUnder(File folder) {
        final var prefix = folder.getAbsolutePath() + File.separator;
        byCollection.keySet().removeIf(collection -> collection.startsWith(prefix));
    }

    private static long knownState(File page) {
        final var address = addressOf(page);
        if (address == null) {
            return UNKNOWN;
        }
        final var pages = byCollection.get(address.collection());
        return pages == null ? UNKNOWN : pages.known(address.page());
    }

    private static long lengthIn(long state) {
        return state >>> 1;
    }

    private static Pages pagesOf(String collection) {
        return byCollection.computeIfAbsent(collection, _ -> new Pages());
    }

    private static Address addressOf(File file) {
        final var path = file.getAbsolutePath();
        if (!path.endsWith(Globals.DB_FILE_EXTENSION)) {
            return null;
        }
        final var end = path.length() - Globals.DB_FILE_EXTENSION.length();
        final var separator = path.lastIndexOf(Globals.FILE_PAGE_SEPARATOR, end - 1);
        if (separator < 0 || separator == end - 1 || path.indexOf(File.separatorChar, separator) >= 0) {
            return null;
        }
        var page = 0L;
        for (var index = separator + 1; index < end; index++) {
            final var digit = path.charAt(index) - '0';
            if (digit < 0 || digit > 9 || page > MAX_TRACKED_PAGE) {
                return null;
            }
            page = page * 10 + digit;
        }
        return page > MAX_TRACKED_PAGE ? null : new Address(path.substring(0, separator), page);
    }

    private static long statted(File page) {
        try {
            return Files.readAttributes(page.toPath(), BasicFileAttributes.class).size();
        } catch (IOException e) {
            return UNKNOWN;
        }
    }

    private static final class Pages {
        private volatile AtomicLongArray states = new AtomicLongArray(0);
        private final AtomicLong changes = new AtomicLong();
        private final ReentrantLock lock = new ReentrantLock();

        long known(long page) {
            final var current = states;
            return page < current.length() ? current.get((int) page) : UNKNOWN;
        }

        long seed(long page, File file) {
            final var seen = changes.get();
            final var length = statted(file);
            if (length < 0) {
                return 0L;
            }
            lock.lock();
            try {
                if (changes.get() == seen && known(page) == UNKNOWN) {
                    store(page, length << 1);
                }
            } finally {
                lock.unlock();
            }
            return length;
        }

        void set(long page, long length, boolean endsWithLineEnd) {
            lock.lock();
            try {
                changes.incrementAndGet();
                store(page, length << 1 | (endsWithLineEnd ? 1L : 0L));
            } finally {
                lock.unlock();
            }
        }

        void forget(long page) {
            lock.lock();
            try {
                changes.incrementAndGet();
                if (page < states.length()) {
                    states.set((int) page, UNKNOWN);
                }
            } finally {
                lock.unlock();
            }
        }

        private void store(long page, long state) {
            var current = states;
            if (page >= current.length()) {
                final var grown = new AtomicLongArray((int) Math.max(page + 1, current.length() * 2L));
                for (var index = 0; index < grown.length(); index++) {
                    grown.set(index, index < current.length() ? current.get(index) : UNKNOWN);
                }
                current = grown;
                states = grown;
            }
            current.set((int) page, state);
        }
    }
}

package org.techhouse.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.log.Logger;

final class WriteIntentRecovery {
    private static final Logger logger = Logger.logFor(WriteIntentRecovery.class);

    private final FilePaths paths;
    private final PkIndexStore pkIndexStore;
    private final WriteIntentJournal journal;

    WriteIntentRecovery(FilePaths paths, PkIndexStore pkIndexStore, WriteIntentJournal journal) {
        this.paths = paths;
        this.pkIndexStore = pkIndexStore;
        this.journal = journal;
    }

    private record Recovered(List<PkIndexEntry> corrections, List<PkIndexEntry> completedDeletes) {
    }

    List<PkIndexEntry> recoverAll() throws IOException {
        final var completedDeletes = new ArrayList<PkIndexEntry>();
        for (final var log : journal.findLogs()) {
            for (final var intent : journal.openIntents(log)) {
                recoverIntent(intent, completedDeletes);
            }
        }
        return completedDeletes;
    }

    private void recoverIntent(WriteIntentJournal.Intent intent, List<PkIndexEntry> completedDeletes)
            throws IOException {
        final var label = intent.dbName() + Globals.COLL_IDENTIFIER_SEPARATOR + intent.collName();
        final var rows = pkIndexStore.readRecognisedPkIndex(intent.dbName(), intent.collName());
        if (rows.isEmpty()) {
            logger.error("Leaving the interrupted write " + intent.label() + " of " + label + " unrecovered: no line of"
                    + " its pk index could be read. Run REINDEX with \"primaryKey\": true on the collection, which"
                    + " recovers this intent and rebuilds the pk index, or repair or restore the pk index; the intent"
                    + " is recovered at the next startup");
            return;
        }
        final var recovered = recover(intent, byId(rows.get()));
        if (recovered == null) {
            logger.error("Leaving the interrupted vacuum " + intent.label() + " of " + label + " unrecovered: a page"
                    + " it rewrote holds one _id twice. Repair the page, then restart or run REINDEX with"
                    + " \"primaryKey\": true");
            return;
        }
        pkIndexStore.appendReplacements(intent.dbName(), intent.collName(), recovered.corrections());
        completedDeletes.addAll(recovered.completedDeletes());
        journal.end(intent);
        logger.warning("Recovered the interrupted write " + intent.label() + " of " + label + " ("
                + intent.items().size() + " item(s)): what its pk index commits was kept, the rest undone");
    }

    private Recovered recover(WriteIntentJournal.Intent intent, Map<String, PkIndexEntry> byId) throws IOException {
        final var dbName = intent.dbName();
        final var collName = intent.collName();
        final var corrections = new ArrayList<PkIndexEntry>();
        final var completedDeletes = new ArrayList<PkIndexEntry>();
        final var uncommitted = new TreeMap<Long, List<WriteIntent.Replace>>();
        for (final var item : intent.items()) {
            switch (item) {
                case WriteIntent.Replace replace -> {
                    final var effective = byId.get(replace.id());
                    if (replace.isRow(effective)) {
                        uncommitted.computeIfAbsent(replace.targetPage(), _ -> new ArrayList<>()).add(replace);
                    } else {
                        blankIfHolds(dbName, collName, replace.page(), replace.position(), replace.length(),
                                replace.id(), effective);
                    }
                }
                case WriteIntent.Delete delete -> {
                    blankIfHolds(dbName, collName, delete.page(), delete.position(), delete.length(), delete.id(),
                            byId.get(delete.id()));
                    if (!byId.containsKey(delete.id())) {
                        completedDeletes.add(delete.preOpRow(dbName, collName));
                    }
                }
                case WriteIntent.Vacuum vacuum -> {
                    Files.deleteIfExists(paths.vacuumFile(dbName, collName, vacuum.page()).toPath());
                    final var rederived = rederive(dbName, collName, vacuum.page(), byId);
                    if (rederived == null) {
                        return null;
                    }
                    corrections.addAll(rederived);
                }
            }
        }
        for (final var target : uncommitted.entrySet()) {
            undoAppends(dbName, collName, target.getKey(), target.getValue(), byId);
        }
        return new Recovered(corrections, completedDeletes);
    }

    int recoverPagesOf(String dbName, String collName) throws IOException {
        var recovered = 0;
        for (final var intent : journal.openIntents(dbName, collName)) {
            recoverPageSide(intent);
            journal.end(intent);
            logger.warning("Recovered the pages of the interrupted write " + intent.label() + " of " + dbName
                    + Globals.COLL_IDENTIFIER_SEPARATOR + collName + " ahead of a PK index rebuild");
            recovered++;
        }
        return recovered;
    }

    private void recoverPageSide(WriteIntentJournal.Intent intent) throws IOException {
        final var dbName = intent.dbName();
        final var collName = intent.collName();
        final var undone = new TreeMap<Long, List<WriteIntent.Replace>>();
        for (final var item : intent.items()) {
            switch (item) {
                case WriteIntent.Replace replace -> {
                    final var page = paths.collectionPage(dbName, collName, replace.page());
                    if (replace.id().equals(PageLines.idAt(page, replace.position(), replace.length()))) {
                        undone.computeIfAbsent(replace.targetPage(), _ -> new ArrayList<>()).add(replace);
                    }
                }
                case WriteIntent.Delete delete -> blankIfHolds(dbName, collName, delete.page(), delete.position(),
                        delete.length(), delete.id(), null);
                case WriteIntent.Vacuum vacuum ->
                    Files.deleteIfExists(paths.vacuumFile(dbName, collName, vacuum.page()).toPath());
            }
        }
        for (final var target : undone.entrySet()) {
            blankAppendedCopies(dbName, collName, target.getKey(), target.getValue(), Map.of());
        }
    }

    List<String> replayableIntentsOf(String dbName, String collName) throws IOException {
        return journal.openIntents(dbName, collName).stream().map(WriteIntentJournal.Intent::label).toList();
    }

    private void undoAppends(String dbName, String collName, long targetPage, List<WriteIntent.Replace> items,
            Map<String, PkIndexEntry> byId) throws IOException {
        final var lengthBefore = lengthBefore(items);
        final var reachesPast = byId.values().stream()
                .anyMatch(row -> row.getPage() == targetPage && row.getPosition() + row.getLength() > lengthBefore);
        if (reachesPast) {
            blankAppendedCopies(dbName, collName, targetPage, items, byId);
            return;
        }
        final var page = paths.collectionPage(dbName, collName, targetPage);
        final var lock = FileLocks.lockWrite(page);
        try {
            if (page.exists() && page.length() > lengthBefore) {
                FileLocks.truncateTo(page, lengthBefore, "Could not truncate the uncommitted copies appended to "
                        + page.getName() + "; a scan may answer them until REINDEX");
            }
        } finally {
            lock.unlock();
        }
    }

    private void blankAppendedCopies(String dbName, String collName, long targetPage, List<WriteIntent.Replace> items,
            Map<String, PkIndexEntry> byId) throws IOException {
        final var ids = new HashSet<String>();
        items.forEach(item -> ids.add(item.id()));
        final var page = paths.collectionPage(dbName, collName, targetPage);
        final var lock = FileLocks.lockWrite(page);
        try (var channel = page.exists() ? FileChannel.open(page.toPath(), StandardOpenOption.WRITE) : null) {
            if (channel == null) {
                return;
            }
            for (final var line : PageLines.recordsOf(page, lengthBefore(items))) {
                final var effective = byId.get(line.id());
                final var referenced = effective != null && effective.getPage() == targetPage
                        && effective.getPosition() == line.position();
                if (ids.contains(line.id()) && !referenced) {
                    PageHoles.blank(channel, line.position(), line.length());
                }
            }
            SyncedWrites.content(channel, page.toPath());
        } finally {
            lock.unlock();
        }
    }

    private static long lengthBefore(List<WriteIntent.Replace> items) {
        return items.stream().mapToLong(WriteIntent.Replace::targetLengthBefore).min().orElse(0L);
    }

    private void blankIfHolds(String dbName, String collName, long pageNumber, long position, long length, String id,
            PkIndexEntry effective) throws IOException {
        if (effective != null && effective.getPage() == pageNumber && effective.getPosition() == position) {
            return;
        }
        final var page = paths.collectionPage(dbName, collName, pageNumber);
        final var lock = FileLocks.lockWrite(page);
        try {
            if (!id.equals(PageLines.idAt(page, position, length))) {
                return;
            }
            try (var channel = FileChannel.open(page.toPath(), StandardOpenOption.WRITE)) {
                PageHoles.blank(channel, position, length);
                SyncedWrites.content(channel, page.toPath());
            }
        } finally {
            lock.unlock();
        }
    }

    private List<PkIndexEntry> rederive(String dbName, String collName, long pageNumber, Map<String, PkIndexEntry> byId)
            throws IOException {
        final var page = paths.collectionPage(dbName, collName, pageNumber);
        final List<PageLines.Line> lines;
        final var lock = FileLocks.lockRead(page);
        try {
            lines = PageLines.recordsOf(page, 0L);
        } finally {
            lock.unlock();
        }
        final var seen = new HashSet<String>();
        final var corrections = new ArrayList<PkIndexEntry>();
        for (final var line : lines) {
            if (line.id() == null) {
                continue;
            }
            if (!seen.add(line.id())) {
                return null;
            }
            final var effective = byId.get(line.id());
            if (effective != null && effective.getPage() == pageNumber
                    && (effective.getPosition() != line.position() || effective.getLength() != line.length())) {
                corrections.add(new PkIndexEntry(dbName, collName, line.id(), line.position(), line.length(),
                        pageNumber, effective.getVersion()));
            }
        }
        return corrections;
    }

    private static Map<String, PkIndexEntry> byId(List<PkIndexEntry> rows) {
        final var byId = new HashMap<String, PkIndexEntry>(rows.size() * 2);
        for (final var row : rows) {
            byId.put(row.getValue(), row);
        }
        return byId;
    }
}

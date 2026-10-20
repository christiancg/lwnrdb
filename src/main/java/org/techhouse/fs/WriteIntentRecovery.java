package org.techhouse.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    void recoverAll() throws IOException {
        for (final var log : journal.findLogs()) {
            for (final var intent : journal.openIntents(log)) {
                recoverIntent(intent);
            }
        }
    }

    private void recoverIntent(WriteIntentJournal.Intent intent) throws IOException {
        final var label = intent.dbName() + Globals.COLL_IDENTIFIER_SEPARATOR + intent.collName();
        final var rows = pkIndexStore.readRecognisedPkIndex(intent.dbName(), intent.collName());
        if (rows.isEmpty()) {
            logger.error("Leaving the interrupted vacuum " + intent.label() + " of " + label + " unrecovered: no line"
                    + " of its pk index could be read. Run REINDEX with \"primaryKey\": true on the collection, which"
                    + " recovers this intent and rebuilds the pk index, or repair or restore the pk index; the intent"
                    + " is recovered at the next startup");
            return;
        }
        final var byId = byId(rows.get());
        final var corrections = new ArrayList<PkIndexEntry>();
        for (final var item : intent.items()) {
            switch (item) {
                case WriteIntent.Vacuum vacuum -> {
                    deleteTemp(intent, vacuum);
                    final var rederived = rederive(intent.dbName(), intent.collName(), vacuum.page(), byId);
                    if (rederived == null) {
                        logger.error("Leaving the interrupted vacuum " + intent.label() + " of " + label
                                + " unrecovered: a page it rewrote holds one _id twice. Repair the page, then restart"
                                + " or run REINDEX with \"primaryKey\": true");
                        return;
                    }
                    corrections.addAll(rederived);
                }
            }
        }
        pkIndexStore.appendReplacements(intent.dbName(), intent.collName(), corrections);
        journal.end(intent);
        logger.warning("Recovered the interrupted vacuum " + intent.label() + " of " + label + ": the pk index now"
                + " names where its records are");
    }

    int recoverPagesOf(String dbName, String collName) throws IOException {
        var recovered = 0;
        for (final var intent : journal.openIntents(dbName, collName)) {
            for (final var item : intent.items()) {
                switch (item) {
                    case WriteIntent.Vacuum vacuum -> deleteTemp(intent, vacuum);
                }
            }
            journal.end(intent);
            logger.warning("Recovered the pages of the interrupted vacuum " + intent.label() + " of " + dbName
                    + Globals.COLL_IDENTIFIER_SEPARATOR + collName + " ahead of a PK index rebuild");
            recovered++;
        }
        return recovered;
    }

    private void deleteTemp(WriteIntentJournal.Intent intent, WriteIntent.Vacuum vacuum) throws IOException {
        Files.deleteIfExists(paths.vacuumFile(intent.dbName(), intent.collName(), vacuum.page()).toPath());
    }

    List<String> replayableIntentsOf(String dbName, String collName) throws IOException {
        return journal.openIntents(dbName, collName).stream().map(WriteIntentJournal.Intent::label).toList();
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

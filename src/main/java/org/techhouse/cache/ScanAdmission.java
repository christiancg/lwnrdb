package org.techhouse.cache;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminPageEntry;

final class ScanAdmission implements Spliterator<DbEntry> {
    private static final Comparator<DbEntry> BY_ID = Comparator.comparing(DbEntry::get_id);

    interface PageReader {
        Collection<DbEntry> read(long page) throws IOException;
    }

    interface Completion {
        void admit(ConcurrentHashMap<String, DbEntry> collected, long measuredBytes);
    }

    private final Iterator<AdminPageEntry> pages;
    private final PageReader reader;
    private final MemoryManagement memoryManagement;
    private final boolean inScanOrder;
    private Completion completion;
    private ConcurrentHashMap<String, DbEntry> collected;
    private long collectedBytes;
    private Iterator<DbEntry> current = Collections.emptyIterator();

    ScanAdmission(List<AdminPageEntry> sortedPages, boolean inScanOrder, PageReader reader,
            MemoryManagement memoryManagement, Completion completion) {
        this.pages = sortedPages.iterator();
        this.inScanOrder = inScanOrder;
        this.reader = reader;
        this.memoryManagement = memoryManagement;
        this.completion = completion;
        this.collected = completion == null ? null : new ConcurrentHashMap<>();
    }

    @Override
    public boolean tryAdvance(Consumer<? super DbEntry> action) {
        while (!current.hasNext()) {
            if (!pages.hasNext()) {
                finish();
                return false;
            }
            current = nextPage();
        }
        action.accept(current.next());
        return true;
    }

    @Override
    public void forEachRemaining(Consumer<? super DbEntry> action) {
        while (true) {
            while (current.hasNext()) {
                action.accept(current.next());
            }
            if (!pages.hasNext()) {
                break;
            }
            current = nextPage();
        }
        finish();
    }

    private Iterator<DbEntry> nextPage() {
        final Collection<DbEntry> page;
        try {
            page = reader.read(pages.next().getPage());
        } catch (IOException e) {
            abandon();
            throw new RuntimeException(e);
        }
        memoryManagement.ensureHeadroomForBytes(CacheSizeEstimator.estimatePageHeap(page));
        collect(page);
        if (!inScanOrder) {
            return page.iterator();
        }
        return page.stream().sorted(BY_ID).iterator();
    }

    private void collect(Collection<DbEntry> page) {
        if (collected == null || page.isEmpty()) {
            return;
        }
        final var pageBytes = CacheSizeEstimator.estimateCollectionEntries(page);
        if (memoryManagement.admissionCheck(collectedBytes + pageBytes) != AdmissionDecision.ADMIT) {
            abandon();
            return;
        }
        for (final var entry : page) {
            collected.put(entry.get_id(), entry);
        }
        collectedBytes += pageBytes;
    }

    private void abandon() {
        completion = null;
        collected = null;
    }

    private void finish() {
        final var admitted = completion;
        final var documents = collected;
        final var bytes = collectedBytes;
        abandon();
        if (admitted != null && !documents.isEmpty()) {
            admitted.admit(documents, bytes);
        }
    }

    @Override
    public Spliterator<DbEntry> trySplit() {
        return null;
    }

    @Override
    public long estimateSize() {
        return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
        return ORDERED | NONNULL;
    }
}

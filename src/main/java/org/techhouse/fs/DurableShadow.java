package org.techhouse.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.techhouse.fs.DurableShadowKeys.PinnedKey;

final class DurableShadow implements Forcer {
    static final String BLOBS = "blobs";
    static final String LISTINGS = "listings";
    static final String LISTING_FILE = ".listing";
    static final String KEY_MAP = "keys.tsv";
    static final String TEMP_SUFFIX = ".tmp";
    static final String SELF = "self";
    static final char FILE = 'f';
    static final char FOLDER = 'd';

    private final Path dataRoot;
    private final Path shadowRoot;
    private final DurableShadowKeys keys = new DurableShadowKeys();
    private final Set<String> frozen = ConcurrentHashMap.newKeySet();
    private final AtomicLong temps = new AtomicLong();
    private final Set<String> mapped = ConcurrentHashMap.newKeySet();
    private final ReentrantLock keyMapLock = new ReentrantLock();

    private DurableShadow(Path dataRoot, Path shadowRoot) {
        this.dataRoot = dataRoot;
        this.shadowRoot = shadowRoot;
    }

    static DurableShadow install(Path dataRoot, Path shadowRoot) throws IOException {
        final var shadow = new DurableShadow(normalized(dataRoot), normalized(shadowRoot));
        shadow.wipe();
        Files.createDirectories(shadow.shadowRoot.resolve(BLOBS));
        Files.createDirectories(shadow.shadowRoot.resolve(LISTINGS));
        shadow.recordBaseline();
        return shadow;
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    @Override
    public void force(FileChannel channel, Path path) throws IOException {
        Forcer.DEVICE.force(channel, path);
        final var target = normalized(path);
        if (!target.startsWith(dataRoot)) {
            return;
        }
        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                recordFolder(target);
            } else {
                recordFile(target);
            }
        } catch (NoSuchFileException ignored) {
        }
    }

    @Override
    public void forceFailed(Path path) {
        try {
            frozen.add(keys.pin(normalized(path)).id());
        } catch (IOException ignored) {
        }
    }

    boolean isFrozen(Path path) throws IOException {
        return frozen.contains(keys.pin(normalized(path)).id());
    }

    Path blobOf(Path path) throws IOException {
        return shadowRoot.resolve(BLOBS).resolve(keys.pin(normalized(path)).id());
    }

    Path listingOf(Path folder) {
        final var relative = dataRoot.relativize(normalized(folder)).toString();
        final var listings = shadowRoot.resolve(LISTINGS);
        return (relative.isEmpty() ? listings : listings.resolve(relative)).resolve(LISTING_FILE);
    }

    void close() throws IOException {
        keys.close();
    }

    private void recordBaseline() throws IOException {
        final List<Path> entries;
        try (Stream<Path> walk = Files.walk(dataRoot)) {
            entries = walk.sorted(Comparator.naturalOrder()).toList();
        }
        for (final var entry : entries) {
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                recordFolder(entry);
            } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                recordFile(entry);
            }
        }
    }

    private void recordFile(Path file) throws IOException {
        final var key = keys.pin(file);
        if (frozen.contains(key.id())) {
            return;
        }
        key.lock().lock();
        try {
            writeAtomically(shadowRoot.resolve(BLOBS).resolve(key.id()), key.readAll());
        } finally {
            key.lock().unlock();
        }
        if (mapped.add(key.id())) {
            mapKey(key);
        }
    }

    private void mapKey(PinnedKey key) throws IOException {
        keyMapLock.lock();
        try {
            Files.writeString(shadowRoot.resolve(BLOBS).resolve(KEY_MAP), key.id() + "\t" + key.fileKey() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } finally {
            keyMapLock.unlock();
        }
    }

    private void recordFolder(Path folder) throws IOException {
        final var key = keys.pin(folder);
        if (frozen.contains(key.id())) {
            return;
        }
        key.lock().lock();
        try {
            final var listing = new StringBuilder();
            listing.append(SELF).append('\t').append(FOLDER).append('\t').append(key.id()).append('\t')
                    .append(key.fileKey()).append('\n');
            final List<Path> children;
            try (Stream<Path> list = Files.list(folder)) {
                children = list.sorted(Comparator.naturalOrder()).toList();
            }
            for (final var child : children) {
                appendChild(listing, child);
            }
            writeAtomically(listingOf(folder), listing.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            key.lock().unlock();
        }
    }

    private void appendChild(StringBuilder listing, Path child) throws IOException {
        final char kind;
        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            kind = FOLDER;
        } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
            kind = FILE;
        } else {
            return;
        }
        final PinnedKey key;
        try {
            key = keys.pin(child);
        } catch (NoSuchFileException ignored) {
            return;
        }
        listing.append(child.getFileName()).append('\t').append(kind).append('\t').append(key.id()).append('\t')
                .append(key.fileKey()).append('\n');
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        final var folder = target.getParent();
        if (folder == null) {
            throw new IOException("a shadow record needs a folder: " + target);
        }
        Files.createDirectories(folder);
        final var temp = Path.of(target + "." + temps.incrementAndGet() + TEMP_SUFFIX);
        Files.write(temp, bytes);
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void wipe() throws IOException {
        if (!Files.exists(shadowRoot)) {
            return;
        }
        final List<Path> entries;
        try (Stream<Path> walk = Files.walk(shadowRoot)) {
            entries = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (final var entry : entries) {
            if (!entry.equals(shadowRoot)) {
                Files.delete(entry);
            }
        }
    }
}

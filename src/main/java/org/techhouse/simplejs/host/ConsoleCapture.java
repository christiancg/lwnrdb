package org.techhouse.simplejs.host;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Bounded, ring-buffered capture of a script's console output. Retains the most recent
 * {@code maxLines} lines, evicting the oldest on overflow, and clips any single line to
 * {@code maxLineChars} so one huge write cannot exhaust the buffer on its own.
 */
public final class ConsoleCapture implements Consumer<String> {
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final int maxLines;
    private final int maxLineChars;
    private boolean truncated;

    public ConsoleCapture(int maxLines, int maxLineChars) {
        this.maxLines = maxLines;
        this.maxLineChars = maxLineChars;
    }

    // Synchronized because a fetch settlement and a coroutine body reach this from different virtual
    // threads, and this is the one piece of interpreter-adjacent state not covered by the coroutine lock.
    @Override
    public synchronized void accept(String line) {
        if (maxLines <= 0) {
            return;
        }
        var text = line == null ? "null" : line;
        if (maxLineChars > 0 && text.length() > maxLineChars) {
            text = text.substring(0, maxLineChars);
            truncated = true;
        }
        lines.addLast(text);
        while (lines.size() > maxLines) {
            lines.removeFirst();
            truncated = true;
        }
    }

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    public synchronized boolean isTruncated() {
        return truncated;
    }
}

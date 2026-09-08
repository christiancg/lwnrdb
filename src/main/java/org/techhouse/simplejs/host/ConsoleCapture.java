package org.techhouse.simplejs.host;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;

public final class ConsoleCapture implements Consumer<String> {
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final int maxLines;
    private final int maxLineChars;
    private boolean truncated;

    public ConsoleCapture(int maxLines, int maxLineChars) {
        this.maxLines = maxLines;
        this.maxLineChars = maxLineChars;
    }

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

package org.techhouse.unit.structure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.techhouse.unit.structure.JavaSourceText.SourceComment;

final class CommentRatchet {
    private CommentRatchet() {
    }

    static Map<String, Integer> read(Path baseline) throws IOException {
        final var counts = new TreeMap<String, Integer>();
        for (final var line : Files.readAllLines(baseline, StandardCharsets.UTF_8)) {
            final var trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                final var separator = trimmed.lastIndexOf(' ');
                counts.put(trimmed.substring(0, separator), Integer.parseInt(trimmed.substring(separator + 1)));
            }
        }
        return counts;
    }

    static String render(Map<String, Integer> counts) {
        final var rendered = new StringBuilder();
        new TreeMap<>(counts).forEach((path, count) -> {
            if (count > 0) {
                rendered.append(path).append(' ').append(count).append('\n');
            }
        });
        return rendered.toString();
    }

    static List<String> problems(Map<String, Integer> current, Map<String, Integer> baseline,
            Map<String, List<SourceComment>> commentsByFile) {
        final var problems = new ArrayList<String>();
        final var paths = new TreeSet<>(current.keySet());
        paths.addAll(baseline.keySet());
        for (final var path : paths) {
            final var allowed = baseline.getOrDefault(path, 0);
            if (!current.containsKey(path)) {
                problems.add(path + ": no such file, delete its baseline line");
                continue;
            }
            final int count = current.get(path);
            if (count > allowed) {
                problems.add(path + ": " + count + " comments, baseline allows " + allowed);
                for (final var comment : commentsByFile.getOrDefault(path, List.of())) {
                    problems.add("    line " + comment.line() + ": " + comment.text());
                }
            } else if (count < allowed) {
                problems.add(count == 0
                        ? path + ": now 0, delete its baseline line"
                        : path + ": now " + count + ", lower its baseline line to " + count);
            }
        }
        return problems;
    }
}

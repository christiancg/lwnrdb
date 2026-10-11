package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.techhouse.unit.structure.JavaSourceText.SourceComment;

public class NoNewCommentsTest {
    static final Path BASELINE = Path.of("config/comment-baseline.txt");
    private static final String FILE = "src/main/java/A.java";

    static Map<String, List<SourceComment>> commentsByFile(List<JavaSources.JavaSourceFile> files) {
        final var byFile = new TreeMap<String, List<SourceComment>>();
        for (final var file : files) {
            final var comments = file.text().comments().stream().filter(comment -> !comment.isToolchainDirective())
                    .toList();
            byFile.put(file.relativePath(), comments);
        }
        return byFile;
    }

    static Map<String, Integer> counts(Map<String, List<SourceComment>> commentsByFile) {
        final var counts = new TreeMap<String, Integer>();
        commentsByFile.forEach((path, comments) -> counts.put(path, comments.size()));
        return counts;
    }

    @Test
    public void test_no_file_has_more_comments_than_its_baseline_and_the_baseline_is_exact() throws Exception {
        final var commentsByFile = commentsByFile(JavaSources.under(JavaSources.MAIN, JavaSources.TEST));
        final var current = counts(commentsByFile);
        if (!Files.exists(BASELINE)) {
            fail(BASELINE + " is missing; create it with exactly these lines:\n" + render(current));
        }
        final var problems = problems(current, read(BASELINE), commentsByFile);
        assertTrue(problems.isEmpty(), "Comments are not allowed (see NO COMMENTS in CLAUDE.md); " + BASELINE
                + " only goes down:\n" + String.join("\n", problems));
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

    @Test
    public void test_matching_counts_have_no_problems() {
        assertTrue(problems(Map.of(FILE, 2, "B.java", 0), Map.of(FILE, 2), Map.of()).isEmpty());
    }

    @Test
    public void test_a_file_above_its_baseline_lists_its_comments() {
        final var problems = problems(Map.of(FILE, 2), Map.of(FILE, 1),
                Map.of(FILE, List.of(new SourceComment(3, "// old"), new SourceComment(9, "// new"))));
        assertEquals(List.of(FILE + ": 2 comments, baseline allows 1", "    line 3: // old", "    line 9: // new"),
                problems);
    }

    @Test
    public void test_a_new_file_with_a_comment_is_a_problem() {
        final var problems = problems(Map.of(FILE, 1), Map.of(), Map.of(FILE, List.of(new SourceComment(1, "// hi"))));
        assertEquals(FILE + ": 1 comments, baseline allows 0", problems.getFirst());
    }

    @Test
    public void test_a_file_below_its_baseline_asks_to_lower_it() {
        assertEquals(List.of(FILE + ": now 2, lower its baseline line to 2"),
                problems(Map.of(FILE, 2), Map.of(FILE, 5), Map.of()));
    }

    @Test
    public void test_a_file_that_reached_zero_asks_to_delete_its_line() {
        assertEquals(List.of(FILE + ": now 0, delete its baseline line"),
                problems(Map.of(FILE, 0), Map.of(FILE, 5), Map.of()));
    }

    @Test
    public void test_a_baseline_line_for_a_missing_file_is_stale() {
        assertEquals(List.of(FILE + ": no such file, delete its baseline line"),
                problems(Map.of(), Map.of(FILE, 1), Map.of()));
    }

    @Test
    public void test_render_round_trips_through_read_and_skips_clean_files() throws Exception {
        final var rendered = render(Map.of("b/B.java", 3, FILE, 1, "Clean.java", 0));
        assertEquals("b/B.java 3\n" + FILE + " 1\n", rendered);
        final var file = Files.createTempFile("comment-baseline", ".txt");
        try {
            Files.writeString(file, rendered + "\n");
            assertEquals(Map.of("b/B.java", 3, FILE, 1), read(file));
        } finally {
            Files.delete(file);
        }
    }
}

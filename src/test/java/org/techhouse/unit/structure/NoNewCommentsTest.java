package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.techhouse.unit.structure.JavaSourceText.SourceComment;

public class NoNewCommentsTest {
    static final Path BASELINE = Path.of("config/comment-baseline.txt");

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
            fail(BASELINE + " is missing; create it with exactly these lines:\n" + CommentRatchet.render(current));
        }
        final var problems = CommentRatchet.problems(current, CommentRatchet.read(BASELINE), commentsByFile);
        assertTrue(problems.isEmpty(), "Comments are not allowed (see NO COMMENTS in CLAUDE.md); " + BASELINE
                + " only goes down:\n" + String.join("\n", problems));
    }
}

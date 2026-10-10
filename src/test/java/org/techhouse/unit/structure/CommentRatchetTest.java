package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.unit.structure.JavaSourceText.SourceComment;

public class CommentRatchetTest {
    private static final String FILE = "src/main/java/A.java";

    @Test
    public void test_matching_counts_have_no_problems() {
        assertTrue(CommentRatchet.problems(Map.of(FILE, 2, "B.java", 0), Map.of(FILE, 2), Map.of()).isEmpty());
    }

    @Test
    public void test_a_file_above_its_baseline_lists_its_comments() {
        final var problems = CommentRatchet.problems(Map.of(FILE, 2), Map.of(FILE, 1),
                Map.of(FILE, List.of(new SourceComment(3, "// old"), new SourceComment(9, "// new"))));
        assertEquals(List.of(FILE + ": 2 comments, baseline allows 1", "    line 3: // old", "    line 9: // new"),
                problems);
    }

    @Test
    public void test_a_new_file_with_a_comment_is_a_problem() {
        final var problems = CommentRatchet.problems(Map.of(FILE, 1), Map.of(),
                Map.of(FILE, List.of(new SourceComment(1, "// hi"))));
        assertEquals(FILE + ": 1 comments, baseline allows 0", problems.getFirst());
    }

    @Test
    public void test_a_file_below_its_baseline_asks_to_lower_it() {
        assertEquals(List.of(FILE + ": now 2, lower its baseline line to 2"),
                CommentRatchet.problems(Map.of(FILE, 2), Map.of(FILE, 5), Map.of()));
    }

    @Test
    public void test_a_file_that_reached_zero_asks_to_delete_its_line() {
        assertEquals(List.of(FILE + ": now 0, delete its baseline line"),
                CommentRatchet.problems(Map.of(FILE, 0), Map.of(FILE, 5), Map.of()));
    }

    @Test
    public void test_a_baseline_line_for_a_missing_file_is_stale() {
        assertEquals(List.of(FILE + ": no such file, delete its baseline line"),
                CommentRatchet.problems(Map.of(), Map.of(FILE, 1), Map.of()));
    }

    @Test
    public void test_render_round_trips_through_read_and_skips_clean_files() throws Exception {
        final var rendered = CommentRatchet.render(Map.of("b/B.java", 3, FILE, 1, "Clean.java", 0));
        assertEquals("b/B.java 3\n" + FILE + " 1\n", rendered);
        final var file = Files.createTempFile("comment-baseline", ".txt");
        try {
            Files.writeString(file, rendered + "\n");
            assertEquals(Map.of("b/B.java", 3, FILE, 1), CommentRatchet.read(file));
        } finally {
            Files.delete(file);
        }
    }
}

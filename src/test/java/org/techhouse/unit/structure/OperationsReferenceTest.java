package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;

public class OperationsReferenceTest {
    private static final Path GUIDE = Path.of("docs/operations.md");
    private static final Pattern CONFIG_ROW = Pattern.compile("^\\| `([A-Za-z]+)` \\|");
    private static final Pattern CHAPTER = Pattern.compile("^##\\s+(.+?)\\s*$");
    private static final String CONFIGURATION_CHAPTER = "Configuration";

    static List<String> configurationRows(MarkdownDocument document) {
        final var rows = new ArrayList<String>();
        var inChapter = false;
        for (final var line : document.proseLines()) {
            final var chapter = CHAPTER.matcher(line.text());
            if (chapter.matches()) {
                inChapter = CONFIGURATION_CHAPTER.equals(chapter.group(1));
                continue;
            }
            final var row = CONFIG_ROW.matcher(line.text());
            if (inChapter && row.find()) {
                rows.add(row.group(1));
            }
        }
        return rows;
    }

    private static List<String> guideRows() throws IOException {
        return configurationRows(MarkdownDocument.read(GUIDE));
    }

    private static TreeSet<String> knownKeys() {
        return ConfigKey.all().stream().map(ConfigKey::key).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    public void test_every_config_key_has_one_row() throws IOException {
        final var rowsPerKey = guideRows().stream()
                .collect(Collectors.groupingBy(key -> key, TreeMap::new, Collectors.counting()));
        final var wrong = new TreeSet<String>();
        for (final var key : knownKeys()) {
            final long rows = rowsPerKey.getOrDefault(key, 0L);
            if (rows != 1) {
                wrong.add(key + " has " + rows + " rows");
            }
        }

        assertTrue(wrong.isEmpty(), "configuration table in " + GUIDE + ": " + wrong);
    }

    @Test
    public void test_no_row_names_an_unknown_key() throws IOException {
        final var known = knownKeys();
        final var unknown = guideRows().stream().filter(key -> !known.contains(key)).toList();

        assertEquals(List.of(), unknown);
    }

    @Test
    public void test_rows_are_read_only_from_the_configuration_chapter() {
        final var document = MarkdownDocument.of(Path.of("guide.md"),
                String.join("\n", "## Running the server", "| `port` | before |", "## Configuration",
                        "| `maxConnections` | counted |", "```", "| `fenced` | skipped |", "```", "### Example",
                        "| `nested` | counted |", "## Metrics", "| `connections` | after |"));

        assertEquals(List.of("maxConnections", "nested"), configurationRows(document));
    }
}

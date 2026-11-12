package org.techhouse.unit.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.unit.structure.MarkdownDocument;

public class ProtocolReferenceTest {
    private static final Path REFERENCE = Path.of("docs/protocol.md");
    private static final Pattern ERROR_ROW = Pattern.compile("^\\| `(\\d{3}-\\d+)` \\| `(\\w+)` \\|\\s*(yes)?\\s*\\|");
    private static final String RETRYABLE = "yes";

    private record ErrorRow(int line, String code, String status, boolean retryable) {
    }

    private static List<ErrorRow> errorRows() throws IOException {
        final var rows = new ArrayList<ErrorRow>();
        for (final var line : MarkdownDocument.read(REFERENCE).proseLines()) {
            final var matcher = ERROR_ROW.matcher(line.text());
            if (matcher.find()) {
                rows.add(new ErrorRow(line.number(), matcher.group(1), matcher.group(2),
                        RETRYABLE.equals(matcher.group(3))));
            }
        }
        return rows;
    }

    private static Map<String, ErrorCode> serverCodes() {
        final var codes = new TreeMap<String, ErrorCode>();
        for (final var code : ErrorCode.values()) {
            codes.put(code.getCode(), code);
        }
        return codes;
    }

    @Test
    public void test_every_operation_has_a_heading() throws IOException {
        final var headings = MarkdownDocument.read(REFERENCE).headings();
        final var missing = new TreeSet<String>();
        for (final var type : OperationType.values()) {
            final var quoted = "`" + type.name() + "`";
            if (headings.stream().noneMatch(heading -> heading.contains(quoted))) {
                missing.add(type.name());
            }
        }

        assertTrue(missing.isEmpty(), "operations without a heading in " + REFERENCE + ": " + missing);
    }

    @Test
    public void test_every_error_code_has_one_row() throws IOException {
        final var rowsPerCode = errorRows().stream()
                .collect(Collectors.groupingBy(ErrorRow::code, TreeMap::new, Collectors.counting()));
        final var wrong = new TreeSet<String>();
        for (final var code : serverCodes().keySet()) {
            final long rows = rowsPerCode.getOrDefault(code, 0L);
            if (rows != 1) {
                wrong.add(code + " has " + rows + " rows");
            }
        }

        assertTrue(wrong.isEmpty(), "error table in " + REFERENCE + ": " + wrong);
    }

    @Test
    public void test_no_row_names_an_unknown_code() throws IOException {
        final var known = serverCodes();
        final var unknown = errorRows().stream().filter(row -> !known.containsKey(row.code()))
                .map(row -> row.code() + " (line " + row.line() + ")").toList();

        assertEquals(List.of(), unknown);
    }

    @Test
    public void test_the_retryable_column_matches_the_server() throws IOException {
        final var wrong = errorRows().stream()
                .filter(row -> row.retryable() != Globals.RETRYABLE_ERROR_CODES.contains(row.code()))
                .map(row -> row.code() + " (line " + row.line() + ")").toList();

        assertEquals(List.of(), wrong);
    }

    @Test
    public void test_the_status_column_matches_the_server() throws IOException {
        final var known = serverCodes();
        final var wrong = errorRows().stream().filter(row -> known.containsKey(row.code()))
                .filter(row -> !known.get(row.code()).getStatus().name().equals(row.status()))
                .map(row -> row.code() + " says " + row.status() + ", the server " + known.get(row.code()).getStatus())
                .toList();

        assertEquals(List.of(), wrong);
    }
}

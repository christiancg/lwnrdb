package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class MarkdownAnchorsTest {
    private static final List<Path> ROOT_DOCUMENTS = List.of(Path.of("README.md"), Path.of("QUICKSTART.md"),
            Path.of("CHANGELOG.md"), Path.of("CONTRIBUTING.md"), Path.of("SECURITY.md"), Path.of("clients/README.md"),
            Path.of("clients/protocol/README.md"));
    private static final Path DOCS = Path.of("docs");
    private static final String MARKDOWN = ".md";
    private static final Set<String> EXTERNAL_SCHEMES = Set.of("http:", "https:", "mailto:");

    private static List<Path> scannedDocuments() throws IOException {
        final var documents = new ArrayList<>(ROOT_DOCUMENTS);
        try (Stream<Path> docs = Files.list(DOCS)) {
            docs.filter(path -> path.toString().endsWith(MARKDOWN)).sorted().forEach(documents::add);
        }
        return documents;
    }

    private static boolean isExternal(String target) {
        return EXTERNAL_SCHEMES.stream().anyMatch(target::startsWith);
    }

    private static Set<String> brokenAnchorLinks(List<Path> documents) throws IOException {
        final var anchorsByFile = new HashMap<Path, Set<String>>();
        final var broken = new TreeSet<String>();
        for (final var path : documents) {
            for (final var link : MarkdownDocument.read(path).links()) {
                if (link.anchor() == null || isExternal(link.target())) {
                    continue;
                }
                final var target = link.file().isEmpty() ? path : resolve(path, link.file());
                if (!target.toString().endsWith(MARKDOWN)) {
                    continue;
                }
                final var anchors = anchorsOf(anchorsByFile, target);
                if (!anchors.contains(link.anchor())) {
                    broken.add(path + ":" + link.line() + " -> " + link.target());
                }
            }
        }
        return broken;
    }

    private static Path resolve(Path source, String file) {
        final var parent = source.getParent();
        return (parent == null ? Path.of(file) : parent.resolve(file)).normalize();
    }

    private static Set<String> anchorsOf(Map<Path, Set<String>> cache, Path target) throws IOException {
        final var cached = cache.get(target);
        if (cached != null) {
            return cached;
        }
        final Set<String> anchors = Files.exists(target) ? MarkdownDocument.read(target).anchors() : Set.of();
        cache.put(target, anchors);
        return anchors;
    }

    @Test
    public void test_every_anchor_link_resolves() throws IOException {
        final var broken = brokenAnchorLinks(scannedDocuments());

        assertTrue(broken.isEmpty(), "links to a heading that does not exist:\n" + String.join("\n", broken));
    }

    @Test
    public void test_github_anchor_rule() {
        assertEquals("cancel_script-admin-only", MarkdownDocument.slug("`CANCEL_SCRIPT` (admin only)"));
        assertEquals("users--permissions", MarkdownDocument.slug("Users & Permissions"));
        assertEquals("client-shell-exec-dumprestore", MarkdownDocument.slug("Client (shell, exec, dump/restore)"));
        assertEquals("writing-a-client-checklist", MarkdownDocument.slug("Writing a client: checklist"));
    }

    @Test
    public void test_a_repeated_heading_gets_a_numbered_anchor() {
        final var document = MarkdownDocument.of(Path.of("x.md"), "# Errors\n\n## Errors\n\n### Errors\n");

        assertEquals(List.of("errors", "errors-1", "errors-2"), List.copyOf(document.anchors()));
    }

    @Test
    public void test_a_hash_line_inside_a_fence_is_not_a_heading() {
        final var document = MarkdownDocument.of(Path.of("x.md"), "# Config\n```\n# the port\n```\n~~~\n# x\n~~~\n");

        assertEquals(List.of("config"), List.copyOf(document.anchors()));
    }

    @Test
    public void test_links_inside_code_spans_are_not_links() {
        final var document = MarkdownDocument.of(Path.of("x.md"),
                "See [`AUTHENTICATE`](#authenticate) and `[a](#not-a-link)`, [t](y.md#z \"title\").\n");

        assertEquals(List.of("#authenticate", "y.md#z"),
                document.links().stream().map(MarkdownDocument.Link::target).toList());
    }

    @Test
    public void test_a_link_to_a_missing_heading_is_reported() throws IOException {
        final var folder = Files.createTempDirectory("anchors");
        final var page = folder.resolve("page.md");
        Files.writeString(page, "# Present\n\n[ok](#present) [gone](#absent) [elsewhere](other.md#x)\n");

        try {
            final var broken = brokenAnchorLinks(List.of(page));

            assertEquals(Set.of(page + ":3 -> #absent", page + ":3 -> other.md#x"), broken);
        } finally {
            Files.delete(page);
            Files.delete(folder);
        }
    }
}

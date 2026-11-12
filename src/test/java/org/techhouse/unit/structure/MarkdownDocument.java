package org.techhouse.unit.structure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MarkdownDocument {
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$");
    private static final Pattern NOT_IN_SLUG = Pattern.compile("[^\\p{L}\\p{N}\\p{M}_\\- ]");
    private static final Pattern CODE_SPAN = Pattern.compile("`[^`]*`");
    private static final Pattern LINK_TARGET = Pattern.compile("]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final String FENCE = "```";
    private static final String ALTERNATE_FENCE = "~~~";

    private final Path path;
    private final List<Line> prose;

    public record Line(int number, String text) {
    }

    public record Link(Path source, int line, String target) {
        public String file() {
            final var hash = target.indexOf('#');
            return hash < 0 ? target : target.substring(0, hash);
        }

        public String anchor() {
            final var hash = target.indexOf('#');
            return hash < 0 ? null : target.substring(hash + 1);
        }
    }

    private MarkdownDocument(Path path, List<Line> prose) {
        this.path = path;
        this.prose = prose;
    }

    public static MarkdownDocument read(Path path) throws IOException {
        return of(path, Files.readString(path, StandardCharsets.UTF_8));
    }

    public static MarkdownDocument of(Path path, String text) {
        final var prose = new ArrayList<Line>();
        var inFence = false;
        final var lines = text.split("\n", -1);
        for (var i = 0; i < lines.length; i++) {
            final var stripped = lines[i].strip();
            if (stripped.startsWith(FENCE) || stripped.startsWith(ALTERNATE_FENCE)) {
                inFence = !inFence;
            } else if (!inFence) {
                prose.add(new Line(i + 1, lines[i]));
            }
        }
        return new MarkdownDocument(path, prose);
    }

    public static String slug(String heading) {
        return NOT_IN_SLUG.matcher(heading.strip().toLowerCase(Locale.ROOT)).replaceAll("").replace(' ', '-');
    }

    public Path path() {
        return path;
    }

    public List<Line> proseLines() {
        return prose;
    }

    public List<String> headings() {
        final var headings = new ArrayList<String>();
        for (final var line : prose) {
            final var matcher = HEADING.matcher(line.text());
            if (matcher.matches()) {
                headings.add(matcher.group(1));
            }
        }
        return headings;
    }

    public Set<String> anchors() {
        final var anchors = new LinkedHashSet<String>();
        final var seen = new HashMap<String, Integer>();
        for (final var heading : headings()) {
            final var base = slug(heading);
            final int earlier = seen.merge(base, 1, Integer::sum) - 1;
            anchors.add(earlier == 0 ? base : base + "-" + earlier);
        }
        return anchors;
    }

    public List<Link> links() {
        final var links = new ArrayList<Link>();
        for (final var line : prose) {
            final var matcher = LINK_TARGET.matcher(CODE_SPAN.matcher(line.text()).replaceAll(""));
            while (matcher.find()) {
                links.add(new Link(path, line.number(), matcher.group(1)));
            }
        }
        return links;
    }
}

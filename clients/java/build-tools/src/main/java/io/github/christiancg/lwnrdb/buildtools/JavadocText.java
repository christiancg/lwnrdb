package io.github.christiancg.lwnrdb.buildtools;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

public final class JavadocText {
    private static final Parser MARKDOWN = Parser.builder().build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().build();
    private static final char BLOCK_TAG_START = '@';
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");

    private JavadocText() {
    }

    public static String toJavadoc(String markdown) {
        final var description = new ArrayList<String>();
        final var blockTags = new ArrayList<String>();
        for (final var line : markdown.split("\n", -1)) {
            final var trimmed = line.strip();
            if (blockTags.isEmpty() && !startsBlockTag(trimmed)) {
                description.add(line);
            } else if (!trimmed.isEmpty()) {
                blockTags.add(INLINE_CODE.matcher(trimmed).replaceAll("{@code $1}"));
            }
        }
        final var html = HTML.render(MARKDOWN.parse(String.join("\n", description))).strip();
        return escapeCommentClose(joined(html, blockTags));
    }

    private static boolean startsBlockTag(String trimmed) {
        return !trimmed.isEmpty() && trimmed.charAt(0) == BLOCK_TAG_START;
    }

    private static String joined(String html, List<String> blockTags) {
        if (blockTags.isEmpty()) {
            return html;
        }
        final var tags = String.join("\n", blockTags);
        return html.isEmpty() ? tags : html + "\n\n" + tags;
    }

    private static String escapeCommentClose(String text) {
        return text.replace("*/", "*&#47;");
    }
}

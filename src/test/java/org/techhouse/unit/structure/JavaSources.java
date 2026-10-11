package org.techhouse.unit.structure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

final class JavaSources {
    static final Path MAIN = Path.of("src/main/java");
    static final Path TEST = Path.of("src/test/java");
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;",
            Pattern.MULTILINE);

    record JavaSourceFile(String relativePath, String packageName, JavaSourceText text) {
        boolean isInPackage(String prefix) {
            return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
        }
    }

    private JavaSources() {
    }

    static List<JavaSourceFile> under(Path... roots) {
        final var files = new ArrayList<JavaSourceFile>();
        for (final var root : roots) {
            try (var paths = Files.walk(root)) {
                paths.filter(path -> path.toString().endsWith(".java")).sorted(Comparator.naturalOrder())
                        .forEach(path -> files.add(read(path)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    static JavaSourceFile parse(String relativePath, String source) {
        final var text = JavaSourceText.of(source);
        final var matcher = PACKAGE_DECLARATION.matcher(text.code());
        return new JavaSourceFile(relativePath, matcher.find() ? matcher.group(1) : "", text);
    }

    private static JavaSourceFile read(Path path) {
        try {
            return parse(path.toString().replace('\\', '/'), Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

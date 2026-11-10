package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.techhouse.unit.structure.JavaSources.JavaSourceFile;

public class ForcerSeamTest {
    private static final Pattern FORCE_CALL = Pattern.compile("(\\w+|\\))\\s*\\.\\s*force\\s*\\(\\s*(\\)?)");
    private static final String FORCER = "/fs/Forcer.java";
    private static final String HELPER = "/fs/SyncedWrites.java";

    static Set<String> forcesOutsideTheSeam(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            final var path = file.relativePath();
            if (path.endsWith(FORCER)) {
                continue;
            }
            final var code = file.text().code();
            final var calls = FORCE_CALL.matcher(code);
            while (calls.find()) {
                if (!isThroughTheSeam(path, code, calls.group(1), !calls.group(2).isEmpty())) {
                    found.add(path + ": " + calls.group());
                }
            }
        }
        return found;
    }

    private static boolean isThroughTheSeam(String path, String code, String receiver, boolean noArguments) {
        if ("DEVICE".equals(receiver) || path.endsWith(HELPER) && "forcer".equals(receiver)) {
            return true;
        }
        return noArguments && !")".equals(receiver) && Pattern
                .compile("ForceGroup\\s+" + receiver + "\\b|\\b" + receiver + "\\s*=\\s*new\\s+ForceGroup\\s*\\(")
                .matcher(code).find();
    }

    @Test
    public void noFileChannelForceOutsideTheForcer() {
        final var found = forcesOutsideTheSeam(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "these calls force a file or folder without going through SyncedWrites'"
                + " Forcer, so the power-loss shadow never records them and an injected force failure never reaches"
                + " them: " + found);
    }

    @Test
    public void theScannerFlagsADirectForce() {
        final var branches = List.of("channel.force(false)", "channel.force(true)", "buffer.force()",
                "out.getChannel() .force(true)", "source().force()");
        for (final var branch : branches) {
            final var source = JavaSources.parse("org/techhouse/fs/Direct.java", """
                    package org.techhouse.fs;
                    class Direct {
                        void sync() throws Exception {
                            %s;
                        }
                    }
                    """.formatted(branch));
            assertEquals(1, forcesOutsideTheSeam(List.of(source)).size(), branch);
        }
    }

    @Test
    public void theSeamAndAForceGroupPass() {
        final var source = JavaSources.parse("org/techhouse/fs/Seamed.java", """
                package org.techhouse.fs;
                class Seamed {
                    void sync(ForceGroup alongside) throws Exception {
                        final var group = new ForceGroup();
                        group.force();
                        alongside.force();
                        Forcer.DEVICE.force(channel, path);
                    }
                }
                """);

        assertEquals(Set.of(), forcesOutsideTheSeam(List.of(source)));
    }
}

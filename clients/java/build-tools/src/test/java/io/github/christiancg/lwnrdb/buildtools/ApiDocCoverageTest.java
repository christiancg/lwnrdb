package io.github.christiancg.lwnrdb.buildtools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

public class ApiDocCoverageTest {
    private static final Path JAVA_ROOT = Path.of(System.getProperty("lwnrdb.javaRoot"));
    private static final List<String> MODULES = List.of(System.getProperty("lwnrdb.apiModules").split(","));

    private static Path sources(String module) {
        return JAVA_ROOT.resolve(module).resolve("src/main/java");
    }

    private static Path docs(String module) {
        return JAVA_ROOT.resolve("apidocs").resolve(module);
    }

    @TestFactory
    public Stream<DynamicTest> test_every_public_member_of_every_published_module_has_api_docs() {
        return MODULES.stream().map(module -> DynamicTest.dynamicTest(module, () -> {
            final var missing = ApiDocCoverage.missing(sources(module), docs(module));
            assertTrue(missing.isEmpty(),
                    "add a section to clients/java/apidocs/" + module + " for:\n" + String.join("\n", missing));
        }));
    }

    @TestFactory
    public Stream<DynamicTest> test_every_api_doc_section_names_a_real_member() {
        return MODULES.stream().map(module -> DynamicTest.dynamicTest(module, () -> new ApiDocInjector(sources(module),
                docs(module), Files.createTempDirectory("documented-" + module)).run()));
    }
}

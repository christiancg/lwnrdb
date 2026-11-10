package io.github.christiancg.lwnrdb.buildtools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class ApiDocCoverageTest {
    private static final Path SOURCES = Path.of(System.getProperty("lwnrdb.clientSources"));
    private static final Path DOCS = Path.of(System.getProperty("lwnrdb.apiDocs"));

    @Test
    public void test_every_public_member_of_the_client_has_api_docs() {
        final var missing = ApiDocCoverage.missing(SOURCES, DOCS);
        assertTrue(missing.isEmpty(), "add a section to clients/java/apidocs for:\n" + String.join("\n", missing));
    }

    @Test
    public void test_every_api_doc_section_names_a_real_member() throws Exception {
        final var output = java.nio.file.Files.createTempDirectory("documented-sources");
        new ApiDocInjector(SOURCES, DOCS, output).run();
    }
}

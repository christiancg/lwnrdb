package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

public class JarManifestTest {
    private static final String FLAG = "--enable-final-field-mutation=ALL-UNNAMED";

    private static Document pom() throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var in = Files.newInputStream(Path.of("pom.xml"))) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static String text(Document pom, String expression) throws Exception {
        final var nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate(expression, pom,
                XPathConstants.NODESET);
        assertEquals(1, nodes.getLength(), expression);
        return nodes.item(0).getTextContent().trim();
    }

    @Test
    public void test_the_jar_manifest_enables_final_field_mutation() throws Exception {
        final var value = text(pom(),
                "//plugin[artifactId='maven-jar-plugin']/configuration/archive/manifestEntries/Enable-Final-Field-Mutation");

        assertEquals("ALL-UNNAMED", value);
    }

    @Test
    public void test_surefire_passes_the_final_field_mutation_flag() throws Exception {
        final var argLine = text(pom(), "//plugin[artifactId='maven-surefire-plugin']/configuration/argLine");

        assertNotNull(argLine);
        assertTrue(argLine.contains(FLAG), argLine);
        assertTrue(argLine.contains("@{argLine}"), argLine);
    }

    @Test
    public void test_an_empty_argline_property_lets_the_flag_resolve_without_the_coverage_agent() throws Exception {
        final var nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate("/project/properties/argLine",
                pom(), XPathConstants.NODESET);

        assertEquals(1, nodes.getLength());
        assertEquals("", nodes.item(0).getTextContent().trim());
    }
}

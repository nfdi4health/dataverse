package edu.harvard.iq.dataverse.search;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Static schema contract checks; these do not start Solr or validate its runtime behavior. */
public class EmbeddingSchemaTest {

    private static final List<String> EMBEDDING_FIELDS = List.of(
            "dataset_embedding", "embedder", "embedding_source_hash", "embedding_pipeline");

    private Document schema;
    private XPath xpath;

    @BeforeEach
    void parseSchema() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        schema = factory.newDocumentBuilder().parse(Path.of("conf/solr/schema.xml").toFile());
        xpath = XPathFactory.newInstance().newXPath();
    }

    @Test
    void embeddingFieldsMatchSearchConstantsAndAttributes() throws Exception {
        assertAll("SearchFields names",
                () -> assertEquals("dataset_embedding", SearchFields.DATASET_EMBEDDING),
                () -> assertEquals("embedder", SearchFields.EMBEDDER),
                () -> assertEquals("embedding_source_hash", SearchFields.EMBEDDING_SOURCE_HASH),
                () -> assertEquals("embedding_pipeline", SearchFields.EMBEDDING_PIPELINE));

        assertField(SearchFields.DATASET_EMBEDDING, "dataset_embedding_vector", "true", "false");
        assertField(SearchFields.EMBEDDER, "string", "true", "true");
        assertField(SearchFields.EMBEDDING_SOURCE_HASH, "string", "false", "true");
        assertField(SearchFields.EMBEDDING_PIPELINE, "string", "true", "true");
    }

    @Test
    void vectorTypeProvidesKeywordOnlyStartupDefaultsWithPropertyOverrides() throws Exception {
        Element vectorType = definition("fieldType", "dataset_embedding_vector");
        assertAll("dataset_embedding_vector",
                () -> assertEquals("solr.DenseVectorField", vectorType.getAttribute("class")),
                () -> assertEquals("FLOAT32", vectorType.getAttribute("vectorEncoding")),
                () -> assertEquals("${solr.embedding.dimension:1}", vectorType.getAttribute("vectorDimension")),
                () -> assertEquals("${solr.embedding.similarity:cosine}", vectorType.getAttribute("similarityFunction")));
    }

    @Test
    void embeddingDefinitionsRemainOutsideGeneratedRegions() throws Exception {
        Set<Node> definitions = new HashSet<>();
        definitions.add(definition("fieldType", "dataset_embedding_vector"));
        for (String field : EMBEDDING_FIELDS) {
            definitions.add(definition("field", field));
        }

        Set<String> generatedRegions = new HashSet<>();
        NodeList children = schema.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.COMMENT_NODE) {
                String marker = child.getNodeValue().trim();
                if (marker.endsWith("::BEGIN")) {
                    generatedRegions.add(marker.substring(0, marker.length() - "::BEGIN".length()));
                } else if (marker.endsWith("::END")) {
                    generatedRegions.remove(marker.substring(0, marker.length() - "::END".length()));
                }
            } else if (definitions.contains(child)) {
                assertTrue(generatedRegions.isEmpty(),
                        ((Element) child).getAttribute("name") + " must be static, outside " + generatedRegions);
            }
        }
    }

    @Test
    void embeddingFieldsAreNotCopiedIntoCatchAllText() throws Exception {
        NodeList copies = (NodeList) xpath.evaluate(
                "/schema/copyField[@dest='_text_']", schema, XPathConstants.NODESET);
        for (int i = 0; i < copies.getLength(); i++) {
            String source = ((Element) copies.item(i)).getAttribute("source");
            // Quote literal field-name characters while honoring Solr's wildcard sources.
            String sourcePattern = Pattern.quote(source).replace("*", "\\E.*\\Q");
            for (String field : EMBEDDING_FIELDS) {
                assertFalse(field.matches(sourcePattern),
                        "copyField source '" + source + "' must not copy " + field + " into _text_");
            }
        }
    }

    private Element definition(String elementName, String name) throws Exception {
        NodeList matches = (NodeList) xpath.evaluate(
                "/schema/" + elementName + "[@name='" + name + "']", schema, XPathConstants.NODESET);
        assertEquals(1, matches.getLength(), "Expected exactly one " + elementName + " named " + name);
        return (Element) matches.item(0);
    }

    private void assertField(String name, String type, String indexed, String stored) throws Exception {
        Element field = definition("field", name);
        assertAll(name,
                () -> assertEquals(type, field.getAttribute("type"), "type"),
                () -> assertEquals(indexed, field.getAttribute("indexed"), "indexed"),
                () -> assertEquals(stored, field.getAttribute("stored"), "stored"),
                () -> assertEquals("false", field.getAttribute("required"), "required"),
                () -> assertEquals("false", field.getAttribute("multiValued"), "multiValued"),
                () -> assertEquals("false", field.getAttribute("docValues"), "docValues"),
                () -> assertFalse(field.hasAttribute("default"), "No default value may be supplied"));
    }
}

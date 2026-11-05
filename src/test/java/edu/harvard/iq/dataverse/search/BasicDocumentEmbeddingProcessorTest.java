package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.junit.jupiter.api.Assertions.*;

@LocalJvmSettings
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class BasicDocumentEmbeddingProcessorTest {

    private EmbeddingConfigurationTest.Settings settings;
    private final DocumentEmbeddingProcessor processor = new BasicDocumentEmbeddingProcessor();

    @BeforeEach
    void configure() {
        settings = new EmbeddingConfigurationTest.Settings();
    }

    @AfterEach
    void restore() {
        settings.close();
    }

    @Test
    void concatenatesHistoricalFieldsInFixedOrderWithoutChangingIndexedText() throws Exception {
        SolrInputDocument document = new SolrInputDocument();
        document.setField("fileContent", "excluded file data");
        document.setField("variableName", "excluded variable metadata");
        document.setField("dsPersistentId", "doi:example");
        document.setField("authorName", "A. Author");
        document.setField("subjects", "Subjects");
        document.setField("subject", "Subject");
        document.setField("description", "Description");
        document.setField("dsDescriptionValue", Arrays.asList("Résumé & observations", null, " ", "second\nparagraph"));
        document.setField("citation", "Citation");
        document.setField("nameSort", "Title");
        document.setField("name", "Name");
        document.setField("title", " <b>Title</b> &amp; ");
        assertEquals(String.join("\n", " <b>Title</b> &amp; ", "Name", "Title", "Citation",
                "Résumé & observations", "second\nparagraph", "Description", "Subject", "Subjects", "A. Author", "doi:example"),
                processor.prepare(document, EmbeddingConfiguration.load()));
    }

    @Test
    void recursivelyFlattensCollectionsAndRetainsDuplicatesAndListOrder() throws Exception {
        SolrInputDocument document = new SolrInputDocument();
        document.setField("title", List.of("same", List.of("last", "first"), "same"));
        document.setField("nameSort", "same");
        assertEquals("same\nlast\nfirst\nsame\nsame", processor.prepare(document, EmbeddingConfiguration.load()));
    }

    @Test
    void sortsSetValuesInsteadOfDependingOnTheirIterationOrder() throws Exception {
        SolrInputDocument document = new SolrInputDocument();
        document.setField("subject", new LinkedHashSet<>(List.of("zebra", "alpha")));
        assertEquals("alpha\nzebra", processor.prepare(document, EmbeddingConfiguration.load()));
        document.setField("subject", new LinkedHashSet<>(List.of("alpha", "zebra")));
        assertEquals("alpha\nzebra", processor.prepare(document, EmbeddingConfiguration.load()));
    }

    @Test
    void emptyMetadataHasNoIdentifierFallbackAndDoesNotEmbedPrefixAlone() throws Exception {
        prefix("Document: ");
        SolrInputDocument document = new SolrInputDocument();
        document.setField(SearchFields.ID, "dataset_42");
        document.setField("title", Arrays.asList("", null, " \t\n"));
        assertEquals("", processor.prepare(document, EmbeddingConfiguration.load()));
    }

    @Test
    void rejectsInputBeyondExplicitCodePointLimitIncludingPrefix() throws Exception {
        prefix("p:");
        settings.set(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, "4");
        SolrInputDocument document = title("😀a");
        assertEquals("p:😀a", processor.prepare(document, EmbeddingConfiguration.load()));
        document.setField("title", "😀ab");
        assertThrows(EmbeddingException.class, () -> processor.prepare(document, EmbeddingConfiguration.load()));
    }

    @Test
    void truncatesContentByCodePointsWhilePreservingTheEntirePrefix() throws Exception {
        prefix("pre-");
        settings.set(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, "7");
        settings.set(JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION, "truncate");
        String text = processor.prepare(title("😀abcde"), EmbeddingConfiguration.load());
        assertEquals("pre-😀ab", text);
        assertEquals(7, text.codePointCount(0, text.length()));
        assertEquals(8, text.length());
    }

    @Test
    void truncationCannotReturnOnlyPrefixOrBlankMetadata() throws Exception {
        prefix("prefix");
        settings.set(JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION, "truncate");
        settings.set(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, "6");
        assertThrows(EmbeddingException.class, () -> processor.prepare(title("data"), EmbeddingConfiguration.load()));
        settings.set(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, "8");
        assertEquals("", processor.prepare(title("   data"), EmbeddingConfiguration.load()));
    }

    @Test
    void pipelineIdentityIsDeterministicAndChangesWithEncodingConfiguration() throws Exception {
        String original = processor.pipelineIdentifier(EmbeddingConfiguration.load());
        assertTrue(original.matches("basic-v1-[0-9a-f]{64}"));
        assertEquals(original, processor.pipelineIdentifier(EmbeddingConfiguration.load()));
        for (JvmSettings key : List.of(JvmSettings.EMBEDDING_NORMALIZATION,
                JvmSettings.EMBEDDING_SIMILARITY, JvmSettings.EMBEDDING_DIMENSIONS,
                JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION, JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS)) {
            String old = System.getProperty(key.getScopedKey());
            String value = switch (key) {
                case EMBEDDING_NORMALIZATION -> "l2";
                case EMBEDDING_SIMILARITY -> "euclidean";
                case EMBEDDING_DIMENSIONS -> "3";
                case EMBEDDING_INPUT_LIMIT_ACTION -> "truncate";
                case EMBEDDING_MAX_INPUT_CODE_POINTS -> "7";
                default -> throw new AssertionError(key);
            };
            settings.set(key, value);
            assertNotEquals(original, processor.pipelineIdentifier(EmbeddingConfiguration.load()), key.name());
            settings.set(key, old);
        }
        prefix("document\n: ");
        assertNotEquals(original, processor.pipelineIdentifier(EmbeddingConfiguration.load()));
    }

    @Test
    void pipelineDoesNotDependOnEndpointSecretsOrTimeouts() throws Exception {
        String original = processor.pipelineIdentifier(EmbeddingConfiguration.load());
        settings.set(JvmSettings.EMBEDDING_ENDPOINT, "https://different.invalid/proxy/api/embed");
        settings.set(JvmSettings.EMBEDDING_AUTH_MODE, "bearer");
        settings.set(JvmSettings.EMBEDDING_AUTH_TOKEN, "private-token");
        settings.set(JvmSettings.EMBEDDING_CONNECT_TIMEOUT_MS, "11");
        settings.set(JvmSettings.EMBEDDING_REQUEST_TIMEOUT_MS, "23");
        assertEquals(original, processor.pipelineIdentifier(EmbeddingConfiguration.load()));
    }

    private void prefix(String prefix) {
        settings.set(JvmSettings.EMBEDDING_DOCUMENT_PREFIX_MODE, "literal");
        settings.set(JvmSettings.EMBEDDING_DOCUMENT_PREFIX, prefix);
    }

    private SolrInputDocument title(String value) {
        SolrInputDocument document = new SolrInputDocument();
        document.setField("title", value);
        return document;
    }
}

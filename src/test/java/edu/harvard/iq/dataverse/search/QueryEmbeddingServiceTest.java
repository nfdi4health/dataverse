package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.settings.FeatureFlags;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.testing.JvmSetting;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import java.util.List;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@LocalJvmSettings
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@JvmSetting(key = JvmSettings.FEATURE_FLAG, varArgs = "semantic-search", value = "true")
@JvmSetting(key = JvmSettings.FEATURE_FLAG, varArgs = "semantic-embeddings", value = "false")
@JvmSetting(key = JvmSettings.SEARCH_SEMANTIC_TOP_K, value = "25")
class QueryEmbeddingServiceTest {

    private static final String SOLR_URL = "http://solr.invalid/solr/collection1";
    private EmbeddingConfigurationTest.Settings settings;
    private QueryEmbeddingService service;

    @BeforeEach
    void configure() throws Exception {
        settings = new EmbeddingConfigurationTest.Settings();
        setSearchFlag("true");
        settings.set(JvmSettings.SEARCH_SEMANTIC_TOP_K, "25");
        settings.set(JvmSettings.EMBEDDING_QUERY_PREFIX_MODE, "none");
        service = new QueryEmbeddingService();
        service.client = mock(EmbeddingClient.class);
        service.schemaValidator = mock(EmbeddingSchemaValidator.class);
        service.processor = spy(new BasicDocumentEmbeddingProcessor());
        when(service.client.embed(anyString(), any())).thenReturn(
                new EmbeddingClient.Result(List.of(1f, 2f, 3f), "client-returned-identity"));
    }

    @AfterEach
    void restore() {
        settings.close();
    }

    @Test
    void nonePreservesNaturalLanguageAndIgnoresUnusedPrefix() throws Exception {
        settings.set(JvmSettings.EMBEDDING_QUERY_PREFIX, "ignored prefix");
        assertPrepared("  ocean observations 😀\n", "  ocean observations 😀\n");
    }

    @Test
    void literalPreservesPrefixWhitespaceAndInsertsNoSeparator() throws Exception {
        for (String prefix : List.of("Query:", "  Instruction:\nQuery: ", "   ")) {
            prefix(prefix);
            assertPrepared(prefix + "oceans", "oceans");
        }
    }

    @Test
    void blankQueryIsRejectedEvenWithNonblankPrefix() throws Exception {
        prefix("Query: ");
        for (String query : new String[]{null, "", " ", "\t\n\u2003"}) {
            assertThrows(EmbeddingException.class, () -> service.embed(query, SOLR_URL));
        }
        verifyNoInteractions(service.client, service.schemaValidator, service.processor);
    }

    @Test
    void prefixModeMustBeExplicitAndRecognized() {
        for (String mode : new String[]{null, "", " ", "auto", "NONE", "private-setting-value"}) {
            settings.set(JvmSettings.EMBEDDING_QUERY_PREFIX_MODE, mode);
            assertSafeSettingFailure(JvmSettings.EMBEDDING_QUERY_PREFIX_MODE);
        }
        verifyNoInteractions(service.client, service.schemaValidator);
    }

    @Test
    void literalRequiresNonemptyPrefix() {
        settings.set(JvmSettings.EMBEDDING_QUERY_PREFIX_MODE, "literal");
        for (String prefix : new String[]{null, ""}) {
            settings.set(JvmSettings.EMBEDDING_QUERY_PREFIX, prefix);
            assertSafeSettingFailure(JvmSettings.EMBEDDING_QUERY_PREFIX);
        }
        verifyNoInteractions(service.client, service.schemaValidator);
    }

    @Test
    void topKMustBeExplicitPositiveJavaInteger() throws Exception {
        for (String value : new String[]{null, "", " ", "0", "-1", "2147483648", "9223372036854775808",
                "1.5", "private-setting-value"}) {
            settings.set(JvmSettings.SEARCH_SEMANTIC_TOP_K, value);
            assertSafeSettingFailure(JvmSettings.SEARCH_SEMANTIC_TOP_K);
        }
        verifyNoInteractions(service.client, service.schemaValidator);
        for (String value : List.of("1", "2147483647")) {
            settings.set(JvmSettings.SEARCH_SEMANTIC_TOP_K, value);
            assertEquals(Integer.parseInt(value), service.embed("oceans", SOLR_URL).topK());
        }
    }

    @Test
    void rejectCountsPrefixAndQueryInUnicodeCodePoints() throws Exception {
        prefix("🔎:");
        settings.set(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, "4");
        assertPrepared("🔎:😀a", "😀a");
        clearInvocations(service.schemaValidator);
        assertThrows(EmbeddingException.class, () -> service.embed("😀ab", SOLR_URL));
        verifyNoInteractions(service.client, service.schemaValidator);
    }

    @Test
    void truncatePreservesWholePrefixAndSurrogatePairsInQuery() throws Exception {
        prefix("🔎:");
        settings.set(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, "4");
        settings.set(JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION, "truncate");
        assertPrepared("🔎:😀a", "😀abc");
        settings.set(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, "3");
        assertPrepared("🔎:😀", "😀abc");
    }

    @Test
    void noneAlsoTruncatesByCodePoints() throws Exception {
        settings.set(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, "2");
        settings.set(JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION, "truncate");
        assertPrepared("😀🌊", "😀🌊abc");
    }

    @Test
    void neitherPolicyCanTruncateThePrefixOrEmbedOnlyThePrefix() throws Exception {
        prefix("🔎prefix");
        for (String action : List.of("reject", "truncate")) {
            settings.set(JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION, action);
            for (String limit : List.of("6", "7")) {
                settings.set(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, limit);
                assertThrows(EmbeddingException.class, () -> service.embed("oceans", SOLR_URL));
            }
        }
        verifyNoInteractions(service.client, service.schemaValidator);
    }

    @Test
    void truncationRejectsBlankRemainingQueryContent() throws Exception {
        prefix("Q:");
        settings.set(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, "4");
        settings.set(JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION, "truncate");
        assertThrows(EmbeddingException.class, () -> service.embed(" \t oceans", SOLR_URL));
        verifyNoInteractions(service.client, service.schemaValidator);
    }

    @Test
    void returnsClientIdentityAndDocumentPipelineWithoutDocumentPreparation() throws Exception {
        assertFalse(FeatureFlags.SEMANTIC_EMBEDDINGS.enabled());
        String pipeline = service.processor.pipelineIdentifier(EmbeddingConfiguration.load());
        clearInvocations(service.processor);
        QueryEmbeddingService.Result result = service.embed("oceans", SOLR_URL);
        assertEquals(List.of(1f, 2f, 3f), result.vector());
        assertEquals("client-returned-identity", result.modelIdentity());
        assertEquals(pipeline, result.pipelineIdentifier());
        assertEquals(25, result.topK());
        assertThrows(UnsupportedOperationException.class, () -> result.vector().set(0, 9f));
        var order = inOrder(service.schemaValidator, service.client, service.processor);
        order.verify(service.schemaValidator).validate(eq(SOLR_URL), any());
        order.verify(service.client).embed(eq("oceans"), any());
        order.verify(service.processor).pipelineIdentifier(any());
        verify(service.processor, never()).prepare(any(), any());
    }

    @Test
    void delegatesPipelineIdentifierRatherThanReconstructingIt() throws Exception {
        service.processor = mock(DocumentEmbeddingProcessor.class);
        when(service.processor.pipelineIdentifier(any())).thenReturn("processor-returned-pipeline");
        assertEquals("processor-returned-pipeline", service.embed("oceans", SOLR_URL).pipelineIdentifier());
        verify(service.processor).pipelineIdentifier(any());
        verifyNoMoreInteractions(service.processor);
    }

    @Test
    void queryPrefixAndTopKChangesNeverChangeDocumentPipeline() throws Exception {
        String pipeline = service.processor.pipelineIdentifier(EmbeddingConfiguration.load());
        for (String prefix : List.of("Query: ", "Instruction:\n", "different")) {
            prefix(prefix);
            settings.set(JvmSettings.SEARCH_SEMANTIC_TOP_K, "100");
            assertEquals(pipeline, service.embed("oceans", SOLR_URL).pipelineIdentifier());
            assertEquals(pipeline, service.processor.pipelineIdentifier(EmbeddingConfiguration.load()));
        }
        settings.set(JvmSettings.EMBEDDING_DOCUMENT_PREFIX_MODE, "literal");
        settings.set(JvmSettings.EMBEDDING_DOCUMENT_PREFIX, "Document: ");
        String changed = service.processor.pipelineIdentifier(EmbeddingConfiguration.load());
        assertNotEquals(pipeline, changed);
        assertEquals(changed, service.embed("oceans", SOLR_URL).pipelineIdentifier());
    }

    @Test
    void absentOrDisabledSemanticFlagRejectsBeforeConfigurationOrTransport() {
        settings.set(JvmSettings.EMBEDDING_QUERY_PREFIX_MODE, null);
        settings.set(JvmSettings.SEARCH_SEMANTIC_TOP_K, null);
        settings.set(JvmSettings.EMBEDDING_ENDPOINT, null);
        for (String flag : new String[]{null, "false"}) {
            setSearchFlag(flag);
            EmbeddingException failure = assertThrows(EmbeddingException.class, () -> service.embed("oceans", SOLR_URL));
            assertEquals("Semantic search is not enabled.", failure.getMessage());
        }
        verifyNoInteractions(service.client, service.schemaValidator);
    }

    @Test
    void documentGenerationCannotEnableSemanticSearch() {
        String generationFlag = JvmSettings.FEATURE_FLAG.insert("semantic-embeddings");
        System.setProperty(generationFlag, "true");
        setSearchFlag("false");
        try {
            EmbeddingException failure = assertThrows(EmbeddingException.class, () -> service.embed("oceans", SOLR_URL));
            assertEquals("Semantic search is not enabled.", failure.getMessage());
            verifyNoInteractions(service.client, service.schemaValidator);
        } finally {
            System.setProperty(generationFlag, "false");
        }
    }

    @Test
    void invalidFeatureFlagFailureIsSanitized() {
        setSearchFlag("private-setting-value");
        EmbeddingException failure = assertThrows(EmbeddingException.class, () -> service.embed("oceans", SOLR_URL));
        assertFalse(failure.getMessage().contains("private-setting-value"));
        assertNull(failure.getCause());
        verifyNoInteractions(service.client, service.schemaValidator);
    }

    @Test
    void missingOrInvalidQuerySettingsDoNotAffectDocumentEmbeddingOrProvenance() throws Exception {
        DatasetEmbeddingService documents = new DatasetEmbeddingService();
        documents.client = service.client;
        documents.schemaValidator = service.schemaValidator;
        documents.processor = service.processor;
        SolrInputDocument document = new SolrInputDocument();
        document.setField("title", "oceans");
        documents.addEmbedding(document, SOLR_URL);
        Object pipeline = document.getFieldValue(SearchFields.EMBEDDING_PIPELINE);
        Object hash = document.getFieldValue(SearchFields.EMBEDDING_SOURCE_HASH);
        for (String mode : new String[]{null, "invalid"}) {
            settings.set(JvmSettings.EMBEDDING_QUERY_PREFIX_MODE, mode);
            settings.set(JvmSettings.EMBEDDING_QUERY_PREFIX, null);
            settings.set(JvmSettings.SEARCH_SEMANTIC_TOP_K, null);
            assertDoesNotThrow(EmbeddingConfiguration::load);
            documents.addEmbedding(document, SOLR_URL);
            assertEquals(pipeline, document.getFieldValue(SearchFields.EMBEDDING_PIPELINE));
            assertEquals(hash, document.getFieldValue(SearchFields.EMBEDDING_SOURCE_HASH));
            assertEquals(List.of(1f, 2f, 3f), List.copyOf(document.getFieldValues(SearchFields.DATASET_EMBEDDING)));
            assertThrows(EmbeddingException.class, () -> service.embed("oceans", SOLR_URL));
        }
    }

    @Test
    void unexpectedCollaboratorFailuresDoNotExposeDetails() throws Exception {
        doThrow(new IllegalStateException("private-setting-value private query http://private.invalid"))
                .when(service.schemaValidator).validate(anyString(), any());
        EmbeddingException failure = assertThrows(EmbeddingException.class, () -> service.embed("private query", SOLR_URL));
        assertEquals("Unable to embed semantic search query.", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        verifyNoInteractions(service.client);
    }

    private void assertPrepared(String expected, String query) throws Exception {
        service.embed(query, SOLR_URL);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(service.client).embed(text.capture(), any());
        assertEquals(expected, text.getValue());
        clearInvocations(service.client);
    }

    private void assertSafeSettingFailure(JvmSettings setting) {
        EmbeddingException failure = assertThrows(EmbeddingException.class, () -> service.embed("oceans", SOLR_URL));
        assertTrue(failure.getMessage().contains(setting.getScopedKey()));
        assertFalse(failure.getMessage().contains("private-setting-value"));
        assertNull(failure.getCause());
    }

    private void prefix(String prefix) {
        settings.set(JvmSettings.EMBEDDING_QUERY_PREFIX_MODE, "literal");
        settings.set(JvmSettings.EMBEDDING_QUERY_PREFIX, prefix);
    }

    private void setSearchFlag(String value) {
        String key = JvmSettings.FEATURE_FLAG.insert("semantic-search");
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}

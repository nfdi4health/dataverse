package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.ControlledVocabularyValue;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetField;
import edu.harvard.iq.dataverse.DatasetFieldCompoundValue;
import edu.harvard.iq.dataverse.DatasetFieldConstant;
import edu.harvard.iq.dataverse.DatasetFieldServiceBean;
import edu.harvard.iq.dataverse.DatasetFieldType;
import edu.harvard.iq.dataverse.DatasetFieldValue;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.DatasetVersionFilesServiceBean;
import edu.harvard.iq.dataverse.DatasetVersionServiceBean;
import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.Dataverse.DataverseType;
import edu.harvard.iq.dataverse.DataverseServiceBean;
import edu.harvard.iq.dataverse.GlobalId;
import edu.harvard.iq.dataverse.MetadataBlock;
import edu.harvard.iq.dataverse.branding.BrandingUtil;
import edu.harvard.iq.dataverse.dataset.DatasetType;
import edu.harvard.iq.dataverse.mocks.MocksFactory;
import edu.harvard.iq.dataverse.pidproviders.doi.AbstractDOIProvider;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.util.SystemConfig;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@LocalJvmSettings
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ExtendWith(MockitoExtension.class)
public class IndexServiceBeanTest {
    private static final Logger logger = Logger.getLogger(IndexServiceBeanTest.class.getCanonicalName());

    private static final String EMBEDDINGS_FLAG = "dataverse.feature.semantic-embeddings";
    private static final String SOLR_URL = "http://solr.invalid/solr/collection1";
    private static final String TITLE = "Océan observations 🌊";
    private static final List<Float> VECTOR = List.of(0.25f, -0.5f, 0.75f);
    private static final String MODEL_IDENTITY = "qwen3-embedding:8b@" + EmbeddingConfigurationTest.DIGEST;
    private static final List<String> EMBEDDING_FIELDS = List.of(SearchFields.DATASET_EMBEDDING,
            SearchFields.EMBEDDER, SearchFields.EMBEDDING_SOURCE_HASH, SearchFields.EMBEDDING_PIPELINE);

    private IndexServiceBean indexService;
    private Dataverse dataverse;
    private EmbeddingConfigurationTest.Settings embeddingSettings;
    private String previousEmbeddingsFlag;

    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private EmbeddingSchemaValidator schemaValidator;
    @Mock
    private SolrClientService solrClientService;
    @Mock
    private SolrClientIndexService solrClientIndexService;
    @Mock
    private SolrClient solrClient;

    @Mock
    private SettingsServiceBean settingsService;
    @InjectMocks
    private SystemConfig systemConfig = new SystemConfig();

    @BeforeEach
    public void setUp() {
        previousEmbeddingsFlag = System.getProperty(EMBEDDINGS_FLAG);
        System.setProperty(EMBEDDINGS_FLAG, "false");
        embeddingSettings = new EmbeddingConfigurationTest.Settings();
        // Only transport/schema boundaries are mocked; a small vector keeps these tests focused.
        embeddingSettings.set(JvmSettings.EMBEDDING_DIMENSIONS, "3");
        dataverse = MocksFactory.makeDataverse();
        dataverse.setDataverseType(DataverseType.UNCATEGORIZED);
        indexService = new IndexServiceBean();
        indexService.systemConfig = systemConfig;
        indexService.settingsService = Mockito.mock(SettingsServiceBean.class);
        indexService.dataverseService = Mockito.mock(DataverseServiceBean.class);
        indexService.datasetFieldService = Mockito.mock(DatasetFieldServiceBean.class);
        indexService.datasetVersionService = Mockito.mock(DatasetVersionServiceBean.class);
        indexService.datasetVersionFilesServiceBean = Mockito.mock(DatasetVersionFilesServiceBean.class);
        indexService.solrClientService = solrClientService;
        indexService.solrClientIndexService = solrClientIndexService;
        DatasetEmbeddingService embeddingService = new DatasetEmbeddingService();
        embeddingService.client = embeddingClient;
        embeddingService.schemaValidator = schemaValidator;
        embeddingService.processor = new BasicDocumentEmbeddingProcessor();
        indexService.datasetEmbeddingService = embeddingService;
        BrandingUtil.injectServices(indexService.dataverseService, indexService.settingsService);

        Mockito.when(indexService.dataverseService.findRootDataverse()).thenReturn(dataverse);
    }

    @AfterEach
    void restoreEmbeddingSettings() {
        embeddingSettings.close();
        if (previousEmbeddingsFlag == null) {
            System.clearProperty(EMBEDDINGS_FLAG);
        } else {
            System.setProperty(EMBEDDINGS_FLAG, previousEmbeddingsFlag);
        }
    }

    @Test
    public void TestIndexing() throws SolrServerException, IOException {
        final IndexableDataset indexableDataset = createIndexableDataset();
        final SolrInputDocuments docs = indexService.toSolrDocs(indexableDataset, null);
        Set<String> indexedFields = docs.getDocuments().stream().flatMap(x -> x.getFieldNames().stream()).collect(Collectors.toSet());

        logger.info(docs.getMessage());
        logger.info(String.join(", ", indexedFields));

        assertTrue(!docs.getDocuments().isEmpty());
        assertTrue(indexedFields.contains("language"));
    }

    @Test
    public void testValidateBoundingBox() throws SolrServerException, IOException {
        final IndexableDataset indexableDataset = createIndexableDataset();
        final DatasetVersion datasetVersion = indexableDataset.getDatasetVersion();
        DatasetField dsf = new DatasetField();
        DatasetFieldType dsft = new DatasetFieldType(DatasetFieldConstant.geographicBoundingBox, DatasetFieldType.FieldType.TEXT, true);
        dsf.setDatasetFieldType(dsft);

        List<DatasetFieldCompoundValue> vals = new LinkedList<>();
        DatasetFieldCompoundValue val = new DatasetFieldCompoundValue();
        val.setParentDatasetField(dsf);
        val.setChildDatasetFields(Arrays.asList(
                constructBoundingBoxValue(DatasetFieldConstant.westLongitude, "34.9"), // bad value. must be less than east
                constructBoundingBoxValue(DatasetFieldConstant.eastLongitude, "34.8"),
                constructBoundingBoxValue(DatasetFieldConstant.northLatitude, "34.2"),
                constructBoundingBoxValue(DatasetFieldConstant.southLatitude, "34.1")
        ));
        vals.add(val);
        dsf.setDatasetFieldCompoundValues(vals);
        datasetVersion.getDatasetFields().add(dsf);

        final SolrInputDocuments docs = indexService.toSolrDocs(indexableDataset, null);
        Optional<SolrInputDocument> doc = docs.getDocuments().stream().findFirst();
        assertTrue(doc.isPresent());
        assertTrue(!doc.get().containsKey("geolocation"));
        assertTrue(!doc.get().containsKey("boundingBox"));
    }

    @Test
    void disabledEmbeddingsLeaveKeywordIndexingUnchanged() throws Exception {
        IndexableDataset dataset = embeddingDataset(DatasetVersion.VersionState.RELEASED, TITLE);
        embeddingSettings.set(JvmSettings.EMBEDDING_ENDPOINT, null);
        SolrInputDocument withoutEndpoint = datasetDocument(indexService.toSolrDocs(dataset, null));

        embeddingSettings.set(JvmSettings.EMBEDDING_ENDPOINT, "http://localhost:11434/api/embed");
        SolrInputDocument withEndpoint = datasetDocument(indexService.toSolrDocs(dataset, null));

        assertKeywordDocument(withEndpoint, dataset, TITLE);
        assertNoEmbedding(withEndpoint);
        assertEquals(withoutEndpoint.getFieldNames(), withEndpoint.getFieldNames());
        for (String field : withoutEndpoint.getFieldNames()) {
            assertEquals(withoutEndpoint.getFieldValues(field), withEndpoint.getFieldValues(field), field);
        }
        Mockito.verifyNoInteractions(embeddingClient, schemaValidator, solrClientService);
    }

    @Test
    void configuredEndpointWithoutFeatureFlagDoesNotRequestEmbeddings() throws Exception {
        // Exercise the absent flag as well as the explicit false used by the other disabled test.
        System.clearProperty(EMBEDDINGS_FLAG);
        assertEquals("http://localhost:11434/api/embed", EmbeddingConfiguration.load().endpoint().toString());
        IndexableDataset dataset = embeddingDataset(DatasetVersion.VersionState.RELEASED, TITLE);

        SolrInputDocument document = datasetDocument(indexService.toSolrDocs(dataset, null));

        assertKeywordDocument(document, dataset, TITLE);
        assertNoEmbedding(document);
        Mockito.verifyNoInteractions(embeddingClient, schemaValidator, solrClientService);
    }

    @Test
    void publishedEmbeddingUsesHistoricalIndexedTextAndReturnedIdentity() throws Exception {
        enableEmbeddings();
        String prefix = "  Document: café\n";
        embeddingSettings.set(JvmSettings.EMBEDDING_DOCUMENT_PREFIX_MODE, "literal");
        embeddingSettings.set(JvmSettings.EMBEDDING_DOCUMENT_PREFIX, prefix);
        Mockito.when(embeddingClient.embed(Mockito.anyString(), Mockito.any(EmbeddingConfiguration.class)))
                .thenReturn(new EmbeddingClient.Result(VECTOR, MODEL_IDENTITY));
        IndexableDataset dataset = embeddingDataset(DatasetVersion.VersionState.RELEASED, TITLE);

        SolrInputDocument document = datasetDocument(indexService.toSolrDocs(dataset, null));

        assertKeywordDocument(document, dataset, TITLE);
        assertEquals(List.of("Résumé des océans"), new ArrayList<>(document.getFieldValues("dsDescriptionValue")));
        // Historical order keeps duplicate title/nameSort values and uses the indexed, HTML-free text.
        String expectedText = prefix + String.join("\n", TITLE, TITLE,
                (String) document.getFieldValue(SearchFields.DATASET_CITATION),
                "Résumé des océans", "Earth and Environmental Sciences", "Curie, Marie",
                (String) document.getFieldValue(SearchFields.DATASET_PERSISTENT_ID));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<EmbeddingConfiguration> configuration = ArgumentCaptor.forClass(EmbeddingConfiguration.class);
        Mockito.verify(embeddingClient).embed(text.capture(), configuration.capture());
        assertEquals(expectedText, text.getValue());
        EmbeddingConfiguration config = configuration.getValue();
        assertAll(
                () -> assertEquals("qwen3-embedding:8b", config.model()),
                () -> assertEquals(EmbeddingConfigurationTest.DIGEST, config.modelRevision().orElseThrow()),
                () -> assertEquals(3, config.dimensions()),
                () -> assertEquals("cosine", config.similarity()),
                () -> assertEquals(VECTOR, new ArrayList<>(document.getFieldValues(SearchFields.DATASET_EMBEDDING))),
                () -> assertEquals(MODEL_IDENTITY, document.getFieldValue(SearchFields.EMBEDDER)),
                () -> assertEquals(utf8Sha256(expectedText), document.getFieldValue(SearchFields.EMBEDDING_SOURCE_HASH)),
                () -> assertNotEquals(utf8Sha256(expectedText.substring(prefix.length())),
                        document.getFieldValue(SearchFields.EMBEDDING_SOURCE_HASH)),
                () -> assertEquals("basic-v1-7cba3eda9e07d7d0605a0d7395f461f72cdd70d20f88a54292b1a52aaa4b2771",
                        document.getFieldValue(SearchFields.EMBEDDING_PIPELINE)),
                () -> assertTrue(document.getFieldValue(SearchFields.EMBEDDING_PIPELINE).toString()
                        .matches("basic-v1-[0-9a-f]{64}")));
        InOrder order = Mockito.inOrder(schemaValidator, embeddingClient);
        order.verify(schemaValidator).validate(SOLR_URL, config);
        order.verify(embeddingClient).embed(expectedText, config);
        Mockito.verifyNoMoreInteractions(embeddingClient, schemaValidator);
    }

    @ParameterizedTest
    @EnumSource(value = DatasetVersion.VersionState.class, names = {"DRAFT", "DEACCESSIONED"})
    void nonPublishedVersionsNeverRequestEmbeddings(DatasetVersion.VersionState state) throws Exception {
        System.setProperty(EMBEDDINGS_FLAG, "true");
        IndexableDataset dataset = embeddingDataset(state, TITLE);

        SolrInputDocument document = datasetDocument(indexService.toSolrDocs(dataset, null));

        assertKeywordDocument(document, dataset, TITLE);
        assertNoEmbedding(document);
        Mockito.verifyNoInteractions(embeddingClient, schemaValidator, solrClientService);
    }

    @Test
    void latestDraftDoesNotContaminatePublishedEmbedding() throws Exception {
        enableEmbeddings();
        Mockito.when(embeddingClient.embed(Mockito.anyString(), Mockito.any(EmbeddingConfiguration.class)))
                .thenReturn(new EmbeddingClient.Result(VECTOR, MODEL_IDENTITY));
        IndexableDataset published = embeddingDataset(DatasetVersion.VersionState.RELEASED, TITLE);
        Dataset dataset = published.getDatasetVersion().getDataset();
        DatasetVersion draft = new DatasetVersion();
        draft.setDataset(dataset);
        draft.setId(MocksFactory.nextId());
        draft.setVersionState(DatasetVersion.VersionState.DRAFT);
        draft.setVersionNumber(2L);
        draft.setMinorVersionNumber(0L);
        draft.setFileMetadatas(new ArrayList<>());
        draft.setDatasetFields(new ArrayList<>(List.of(
                textField("title", "PRIVATE DRAFT TITLE", DatasetFieldType.FieldType.TEXT),
                compoundField("dsDescription", textField("dsDescriptionValue", "PRIVATE DRAFT DESCRIPTION",
                        DatasetFieldType.FieldType.TEXTBOX)),
                createCVVField("language", "English", false))));
        dataset.setVersions(Arrays.asList(draft, published.getDatasetVersion()));
        assertSame(draft, dataset.getLatestVersion());

        SolrInputDocument publishedDocument = datasetDocument(indexService.toSolrDocs(published, null));
        SolrInputDocument draftDocument = datasetDocument(indexService.toSolrDocs(new IndexableDataset(draft), null));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        Mockito.verify(embeddingClient).embed(text.capture(), Mockito.any(EmbeddingConfiguration.class));
        assertTrue(text.getValue().contains(TITLE));
        assertTrue(text.getValue().contains("Résumé des océans"));
        assertFalse(text.getValue().contains("PRIVATE DRAFT"));
        assertKeywordDocument(publishedDocument, published, TITLE);
        assertEquals(VECTOR, new ArrayList<>(publishedDocument.getFieldValues(SearchFields.DATASET_EMBEDDING)));
        assertEquals(List.of("PRIVATE DRAFT TITLE"), new ArrayList<>(draftDocument.getFieldValues("title")));
        assertNoEmbedding(draftDocument);
        Mockito.verify(schemaValidator).validate(Mockito.eq(SOLR_URL), Mockito.any(EmbeddingConfiguration.class));
        Mockito.verify(solrClientService).getSolrUrl();
        Mockito.verifyNoMoreInteractions(embeddingClient, schemaValidator, solrClientService);
    }

    @Test
    void missingConfigurationStillSubmitsCompleteKeywordDocument() throws Exception {
        enableEmbeddings();
        embeddingSettings.set(JvmSettings.EMBEDDING_MODEL, null);
        IndexableDataset dataset = embeddingDataset(DatasetVersion.VersionState.RELEASED, TITLE);
        List<SolrInputDocument> submissions = new ArrayList<>();
        Map<String, SolrInputDocument> stored = mockReplacementIndex(submissions);

        submitDataset(dataset);

        assertEquals(1, submissions.size());
        assertKeywordDocument(stored.get(dataset.getSolrDocId()), dataset, TITLE);
        assertNoEmbedding(stored.get(dataset.getSolrDocId()));
        Mockito.verifyNoInteractions(embeddingClient, schemaValidator);
        Mockito.verify(solrClient).add(Mockito.<SolrInputDocument>anyCollection());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Solr embedding vectorDimension does not match the configured dimensions.",
            "Solr embedding similarityFunction does not match the configured similarity.",
            "Unable to validate the active Solr embedding schema."
    })
    void schemaFailuresStillSubmitCompleteKeywordDocument(String reason) throws Exception {
        enableEmbeddings();
        Mockito.doThrow(new EmbeddingException(reason)).when(schemaValidator)
                .validate(Mockito.eq(SOLR_URL), Mockito.any(EmbeddingConfiguration.class));
        IndexableDataset dataset = embeddingDataset(DatasetVersion.VersionState.RELEASED, TITLE);
        List<SolrInputDocument> submissions = new ArrayList<>();
        Map<String, SolrInputDocument> stored = mockReplacementIndex(submissions);

        submitDataset(dataset);

        assertEquals(1, submissions.size());
        assertKeywordDocument(stored.get(dataset.getSolrDocId()), dataset, TITLE);
        assertNoEmbedding(stored.get(dataset.getSolrDocId()));
        Mockito.verifyNoInteractions(embeddingClient);
        Mockito.verify(solrClient).add(Mockito.<SolrInputDocument>anyCollection());
    }

    @Test
    void clientFailureReplacesPreviouslyEmbeddedDocumentWithoutStaleFields() throws Exception {
        enableEmbeddings();
        Mockito.when(embeddingClient.embed(Mockito.anyString(), Mockito.any(EmbeddingConfiguration.class)))
                .thenReturn(new EmbeddingClient.Result(VECTOR, MODEL_IDENTITY))
                .thenThrow(new EmbeddingException("Embedding request timed out."));
        IndexableDataset dataset = embeddingDataset(DatasetVersion.VersionState.RELEASED, TITLE);
        List<SolrInputDocument> submissions = new ArrayList<>();
        Map<String, SolrInputDocument> stored = mockReplacementIndex(submissions);
        submitDataset(dataset);
        SolrInputDocument embedded = stored.get(dataset.getSolrDocId());
        EMBEDDING_FIELDS.forEach(field -> assertTrue(embedded.containsKey(field), field));

        submitDataset(new IndexableDataset(dataset.getDatasetVersion()));

        assertEquals(2, submissions.size());
        SolrInputDocument replacement = stored.get(dataset.getSolrDocId());
        assertEquals(1, stored.size());
        assertNotSame(embedded, replacement);
        assertNotSame(submissions.get(0), submissions.get(1));
        assertEquals(submissions.get(0).getFieldValue(SearchFields.ID), submissions.get(1).getFieldValue(SearchFields.ID));
        assertKeywordDocument(replacement, dataset, TITLE);
        assertNoEmbedding(replacement);
        Mockito.verify(embeddingClient, Mockito.times(2)).embed(Mockito.anyString(), Mockito.any(EmbeddingConfiguration.class));
        Mockito.verify(solrClient, Mockito.times(2)).add(Mockito.<SolrInputDocument>anyCollection());
        Mockito.verifyNoMoreInteractions(solrClient);
    }

    @Test
    void embeddingFailureRemovesAllFourExistingEmbeddingFields() throws Exception {
        IndexableDataset dataset = embeddingDataset(DatasetVersion.VersionState.RELEASED, TITLE);
        SolrInputDocument document = datasetDocument(indexService.toSolrDocs(dataset, null));
        document.setField(SearchFields.DATASET_EMBEDDING, VECTOR);
        document.setField(SearchFields.EMBEDDER, MODEL_IDENTITY);
        document.setField(SearchFields.EMBEDDING_SOURCE_HASH, "stale hash");
        document.setField(SearchFields.EMBEDDING_PIPELINE, "stale pipeline");
        enableEmbeddings();
        Mockito.when(embeddingClient.embed(Mockito.anyString(), Mockito.any(EmbeddingConfiguration.class)))
                .thenThrow(new EmbeddingException("Embedding transport or response failure."));

        Method addEmbedding = IndexServiceBean.class.getDeclaredMethod("addDatasetEmbedding",
                IndexableDataset.class, SolrInputDocument.class);
        addEmbedding.setAccessible(true);
        addEmbedding.invoke(indexService, dataset, document);

        assertNoEmbedding(document);
        assertKeywordDocument(document, dataset, TITLE);
        Mockito.verify(embeddingClient).embed(Mockito.anyString(), Mockito.any(EmbeddingConfiguration.class));
    }

    @Test
    void disablingFlagReplacesPreviouslyEmbeddedDocumentWithoutStaleFields() throws Exception {
        enableEmbeddings();
        Mockito.when(embeddingClient.embed(Mockito.anyString(), Mockito.any(EmbeddingConfiguration.class)))
                .thenReturn(new EmbeddingClient.Result(VECTOR, MODEL_IDENTITY));
        IndexableDataset dataset = embeddingDataset(DatasetVersion.VersionState.RELEASED, TITLE);
        List<SolrInputDocument> submissions = new ArrayList<>();
        Map<String, SolrInputDocument> stored = mockReplacementIndex(submissions);
        submitDataset(dataset);
        SolrInputDocument embedded = stored.get(dataset.getSolrDocId());
        EMBEDDING_FIELDS.forEach(field -> assertTrue(embedded.containsKey(field), field));
        Mockito.clearInvocations(embeddingClient, schemaValidator, solrClientService);

        System.setProperty(EMBEDDINGS_FLAG, "false");
        submitDataset(new IndexableDataset(dataset.getDatasetVersion()));

        assertEquals(2, submissions.size());
        assertEquals(1, stored.size());
        assertNotSame(embedded, stored.get(dataset.getSolrDocId()));
        assertNotSame(submissions.get(0), submissions.get(1));
        assertEquals(submissions.get(0).getFieldValue(SearchFields.ID), submissions.get(1).getFieldValue(SearchFields.ID));
        assertKeywordDocument(stored.get(dataset.getSolrDocId()), dataset, TITLE);
        assertNoEmbedding(stored.get(dataset.getSolrDocId()));
        Mockito.verifyNoInteractions(embeddingClient, schemaValidator, solrClientService);
        Mockito.verify(solrClient, Mockito.times(2)).add(Mockito.<SolrInputDocument>anyCollection());
        Mockito.verifyNoMoreInteractions(solrClient);
    }

    @Test
    void unexpectedClientFailureLogsSanitizedWarningAndStillSubmitsKeywords() throws Exception {
        enableEmbeddings();
        String sensitive = "https://user:private-token@embedding.invalid/ " + TITLE;
        Mockito.when(embeddingClient.embed(Mockito.anyString(), Mockito.any(EmbeddingConfiguration.class)))
                .thenThrow(new IllegalStateException(sensitive));
        IndexableDataset dataset = embeddingDataset(DatasetVersion.VersionState.RELEASED, TITLE);
        List<SolrInputDocument> submissions = new ArrayList<>();
        Map<String, SolrInputDocument> stored = mockReplacementIndex(submissions);
        Logger indexLogger = Logger.getLogger(IndexServiceBean.class.getCanonicalName());
        Handler handler = Mockito.mock(Handler.class);
        indexLogger.addHandler(handler);
        try {
            submitDataset(dataset);
        } finally {
            indexLogger.removeHandler(handler);
        }

        assertEquals(1, submissions.size());
        assertKeywordDocument(stored.get(dataset.getSolrDocId()), dataset, TITLE);
        assertNoEmbedding(stored.get(dataset.getSolrDocId()));
        ArgumentCaptor<LogRecord> records = ArgumentCaptor.forClass(LogRecord.class);
        Mockito.verify(handler, Mockito.atLeastOnce()).publish(records.capture());
        List<LogRecord> warnings = records.getAllValues().stream()
                .filter(record -> record.getLevel().equals(Level.WARNING)).collect(Collectors.toList());
        assertEquals(1, warnings.size());
        LogRecord warning = warnings.get(0);
        String message = MessageFormat.format(warning.getMessage(), warning.getParameters());
        assertTrue(message.contains("Unexpected embedding configuration or processing failure."));
        assertTrue(message.contains("Keyword indexing continues"));
        assertTrue(message.contains("retry using dataset reindexing"));
        assertEquals(dataset.getDatasetVersion().getDataset().getId(), warning.getParameters()[0]);
        assertEquals(dataset.getDatasetVersion().getId(), warning.getParameters()[1]);
        assertFalse(message.contains("private-token"));
        assertFalse(message.contains("embedding.invalid"));
        assertFalse(message.contains(TITLE));
        assertNull(warning.getThrown());
        Mockito.verify(solrClient).add(Mockito.<SolrInputDocument>anyCollection());
    }

    private void enableEmbeddings() {
        System.setProperty(EMBEDDINGS_FLAG, "true");
        Mockito.when(solrClientService.getSolrUrl()).thenReturn(SOLR_URL);
    }

    private IndexableDataset embeddingDataset(DatasetVersion.VersionState state, String title) {
        DatasetVersion version = createIndexableDataset().getDatasetVersion();
        version.setId(MocksFactory.nextId());
        version.setVersionState(state);
        version.setVersionNumber(1L);
        version.setMinorVersionNumber(0L);
        version.setReleaseTime(new Date(1609459200000L));
        version.setLastUpdateTime(new Date(1609459200000L));
        version.getDatasetFields().add(textField("title", title, DatasetFieldType.FieldType.TEXT));
        version.getDatasetFields().add(compoundField("dsDescription",
                textField("dsDescriptionValue", "<p>Résumé des <b>océans</b></p>", DatasetFieldType.FieldType.TEXTBOX)));
        version.getDatasetFields().add(createCVVField("subject", "Earth and Environmental Sciences", true));
        version.getDatasetFields().add(compoundField("author",
                textField("authorName", "Curie, Marie", DatasetFieldType.FieldType.TEXT)));
        // IndexableDataset captures the state at construction, so construct it only after changing the version.
        return new IndexableDataset(version);
    }

    private static DatasetField textField(String name, String value, DatasetFieldType.FieldType type) {
        DatasetFieldType fieldType = new DatasetFieldType(name, type, false);
        fieldType.setId(MocksFactory.nextId());
        MetadataBlock block = new MetadataBlock();
        block.setName("citation");
        fieldType.setMetadataBlock(block);
        DatasetField field = new DatasetField();
        field.setId(MocksFactory.nextId());
        field.setDatasetFieldType(fieldType);
        field.setSingleValue(value);
        return field;
    }

    private static DatasetField compoundField(String name, DatasetField child) {
        DatasetFieldType type = new DatasetFieldType(name, DatasetFieldType.FieldType.NONE, true);
        type.setId(MocksFactory.nextId());
        type.setChildDatasetFieldTypes(List.of(child.getDatasetFieldType()));
        type.setMetadataBlock(child.getDatasetFieldType().getMetadataBlock());
        child.getDatasetFieldType().setParentDatasetFieldType(type);
        DatasetField field = new DatasetField();
        field.setId(MocksFactory.nextId());
        field.setDatasetFieldType(type);
        DatasetFieldCompoundValue value = new DatasetFieldCompoundValue();
        value.setParentDatasetField(field);
        value.setChildDatasetFields(List.of(child));
        child.setParentDatasetFieldCompoundValue(value);
        field.setDatasetFieldCompoundValues(List.of(value));
        return field;
    }

    private static SolrInputDocument datasetDocument(SolrInputDocuments documents) {
        return documents.getDocuments().stream()
                .filter(document -> "datasets".equals(document.getFieldValue(SearchFields.TYPE)))
                .findFirst().orElseThrow();
    }

    private static void assertKeywordDocument(SolrInputDocument document, IndexableDataset dataset, String title) {
        assertNotNull(document);
        assertAll(
                () -> assertEquals(dataset.getSolrDocId(), document.getFieldValue(SearchFields.ID)),
                () -> assertEquals("datasets", document.getFieldValue(SearchFields.TYPE)),
                () -> assertEquals("English", document.getFieldValue("language")),
                () -> assertEquals(List.of(title), new ArrayList<>(document.getFieldValues("title"))),
                () -> assertEquals(List.of(title), new ArrayList<>(document.getFieldValues(SearchFields.NAME_SORT))),
                () -> assertNotNull(document.getFieldValue(SearchFields.DATASET_CITATION)),
                () -> assertEquals(dataset.getDatasetVersion().getDataset().getGlobalId().toString(),
                        document.getFieldValue(SearchFields.DATASET_PERSISTENT_ID)));
    }

    private static void assertNoEmbedding(SolrInputDocument document) {
        EMBEDDING_FIELDS.forEach(field -> assertFalse(document.containsKey(field), field));
    }

    private static String utf8Sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, SolrInputDocument> mockReplacementIndex(List<SolrInputDocument> submissions) throws Exception {
        Map<String, SolrInputDocument> stored = new HashMap<>();
        Mockito.when(solrClientIndexService.getSolrClient()).thenReturn(solrClient);
        Mockito.doAnswer(invocation -> {
            Collection<SolrInputDocument> documents = invocation.getArgument(0);
            for (SolrInputDocument document : documents) {
                for (String field : document.getFieldNames()) {
                    Collection<Object> values = document.getFieldValues(field);
                    if (values != null) {
                        values.forEach(value -> assertFalse(value instanceof Map,
                                "Expected a full document, not an atomic update: " + field));
                    }
                }
                SolrInputDocument snapshot = document.deepCopy();
                submissions.add(snapshot);
                // Solr add replaces the entire document for this ID, rather than merging omitted fields.
                stored.put((String) snapshot.getFieldValue(SearchFields.ID), snapshot.deepCopy());
            }
            return null;
        }).when(solrClient).add(Mockito.<SolrInputDocument>anyCollection());
        return stored;
    }

    private void submitDataset(IndexableDataset dataset) throws Exception {
        Method addOrUpdate = IndexServiceBean.class.getDeclaredMethod("addOrUpdateDataset", IndexableDataset.class);
        addOrUpdate.setAccessible(true);
        String message = (String) addOrUpdate.invoke(indexService, dataset);
        assertTrue(message.contains("indexed dataset"));
    }

    private DatasetField constructBoundingBoxValue(String datasetFieldTypeName, String value) {
        DatasetField retVal = new DatasetField();
        retVal.setDatasetFieldType(new DatasetFieldType(datasetFieldTypeName, DatasetFieldType.FieldType.TEXT, false));
        retVal.setDatasetFieldValues(Collections.singletonList(new DatasetFieldValue(retVal, value)));
        return retVal;
    }

    private IndexableDataset createIndexableDataset() {
        final Dataset dataset = MocksFactory.makeDataset();
        dataset.setGlobalId(new GlobalId(AbstractDOIProvider.DOI_PROTOCOL,"10.666", "FAKE/fake", "/", AbstractDOIProvider.DOI_RESOLVER_URL, null));
        final DatasetVersion datasetVersion = dataset.getCreateVersion(null);
        DatasetField field = createCVVField("language", "English", false);
        datasetVersion.getDatasetFields().add(field);
        final IndexableDataset indexableDataset = new IndexableDataset(datasetVersion);
        indexableDataset.getDatasetVersion().getDataset().setOwner(dataverse);
        DatasetType datasetType = new DatasetType();
        datasetType.setName(DatasetType.DEFAULT_DATASET_TYPE);
        indexableDataset.getDatasetVersion().getDataset().setDatasetType(datasetType);
        return indexableDataset;
    }

    public static DatasetField createCVVField(String name, String strValue, boolean isAllowMultiples) {
        DatasetFieldType datasetFieldType = new DatasetFieldType(name, DatasetFieldType.FieldType.TEXT, isAllowMultiples);
        ControlledVocabularyValue value = new ControlledVocabularyValue(MocksFactory.nextId(), strValue, datasetFieldType);
        datasetFieldType.setControlledVocabularyValues(Arrays.asList(value));
        datasetFieldType.setId(MocksFactory.nextId());
        datasetFieldType.setMetadataBlock(new MetadataBlock());
        DatasetField field = new DatasetField();
        field.setId(MocksFactory.nextId());
        field.setSingleControlledVocabularyValue(value);
        field.setDatasetFieldType(datasetFieldType);
        return field;
    }
}

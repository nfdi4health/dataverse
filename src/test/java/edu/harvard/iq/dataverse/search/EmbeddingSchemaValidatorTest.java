package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@LocalJvmSettings
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class EmbeddingSchemaValidatorTest {

    private EmbeddingConfigurationTest.Settings settings;
    private final EmbeddingSchemaValidator validator = new EmbeddingSchemaValidator();
    private final List<SolrRequest<?>> requests = new ArrayList<>();
    private Map<String, Object> field;
    private Map<String, Object> fieldType;
    private SolrClient client;

    @BeforeEach
    void configure() throws Exception {
        settings = new EmbeddingConfigurationTest.Settings();
        field = new LinkedHashMap<>(Map.of(
                "name", SearchFields.DATASET_EMBEDDING,
                "type", "deployment_vector",
                "multiValued", false, "indexed", true, "stored", false));
        fieldType = new LinkedHashMap<>(Map.of(
                "name", "deployment_vector", "class", "solr.DenseVectorField",
                "vectorDimension", 4096, "similarityFunction", "cosine", "vectorEncoding", "FLOAT32"));
        client = mock(SolrClient.class);
        doAnswer(invocation -> {
            SolrRequest<?> request = invocation.getArgument(0);
            requests.add(request);
            if (request instanceof SchemaRequest.Field) {
                assertEquals("/schema/fields/" + SearchFields.DATASET_EMBEDDING, request.getPath());
                assertTrue(request.getParams().getBool("showDefaults", false));
                return response("field", field);
            }
            assertInstanceOf(SchemaRequest.FieldType.class, request);
            assertEquals("/schema/fieldtypes/" + field.get("type"), request.getPath());
            return response("fieldType", fieldType);
        }).when(client).request(any(SolrRequest.class), isNull());
    }

    @AfterEach
    void restore() {
        settings.close();
    }

    @Test
    void rejectsByteOrUnresolvedVectorEncodingDespiteMatchingDimensions() {
        for (String encoding : List.of("BYTE", "${encoding}")) {
            fieldType.put("vectorEncoding", encoding);
            assertThrows(EmbeddingException.class, () -> validator.validate(client, EmbeddingConfiguration.load()));
        }
        fieldType.remove("vectorEncoding");
        assertThrows(EmbeddingException.class, () -> validator.validate(client, EmbeddingConfiguration.load()));
    }

    @Test
    void acceptsMatchingResolvedActiveSchemaAndLeavesInjectedClientOpen() throws Exception {
        assertDoesNotThrow(() -> validator.validate(client, EmbeddingConfiguration.load()));
        assertEquals(2, requests.size());
        verify(client, never()).close();
    }

    @Test
    void resolvesActualTypeNameOnEveryValidationWithoutCaching() throws Exception {
        EmbeddingConfiguration config = EmbeddingConfiguration.load();
        for (String type : List.of("deployment_vector", "renamed_vector", "dataset_embedding_vector")) {
            field.put("type", type);
            fieldType.put("name", type);
            validator.validate(client, config);
        }
        assertEquals(6, requests.size());
        fieldType.put("vectorDimension", config.dimensions() + 1);
        assertThrows(EmbeddingException.class, () -> validator.validate(client, config));
        assertEquals(8, requests.size());
    }

    @Test
    void acceptsBothDenseVectorClassNamesAndStringOrIntegerAttributes() throws Exception {
        for (String fieldClass : List.of("solr.DenseVectorField", "org.apache.solr.schema.DenseVectorField")) {
            fieldType.put("class", fieldClass);
            for (Object dimension : List.of(4096, "4096", 4096L)) {
                fieldType.put("vectorDimension", dimension);
                field.put("multiValued", "false");
                field.put("indexed", "true");
                field.put("stored", "false");
                validator.validate(client, EmbeddingConfiguration.load());
            }
        }
    }

    @Test
    void usesRuntimeConfiguredDimensionRatherThanAStaticDeploymentDimension() throws Exception {
        settings.set(JvmSettings.EMBEDDING_DIMENSIONS, "17");
        fieldType.put("vectorDimension", 17);
        validator.validate(client, EmbeddingConfiguration.load());
        fieldType.put("vectorDimension", 4096);
        assertFailureContains("vectorDimension");
    }

    @Test
    void rejectsMissingNullInvalidNonpositiveUnresolvedAndMismatchedDimensions() throws Exception {
        for (Object value : new Object[]{null, "", " ", 0, -1, "0", "-1", 4095,
                "${solr.embedding.dimension}", "${solr.embedding.dimension:4096}",
                "4096.0", 4096.0, "1.5", "2147483648", true, "private-schema-value"}) {
            fieldType.put("vectorDimension", value);
            assertFailureContains("vectorDimension");
        }
        fieldType.remove("vectorDimension");
        assertFailureContains("vectorDimension");
    }

    @Test
    void requiresExactResolvedSimilarity() throws Exception {
        for (Object value : new Object[]{null, "", " ", "dot_product", "euclidean",
                "maximum_inner_product", "COSINE", "cosine ", true,
                "${solr.embedding.similarity}", "private-schema-value"}) {
            fieldType.put("similarityFunction", value);
            assertFailureContains("similarityFunction");
        }
        fieldType.remove("similarityFunction");
        assertFailureContains("similarityFunction");
    }

    @Test
    void dotProductRequiresExplicitL2Normalization() throws Exception {
        settings.set(JvmSettings.EMBEDDING_SIMILARITY, "dot_product");
        fieldType.put("similarityFunction", "dot_product");
        assertFailureContains("l2");
        settings.set(JvmSettings.EMBEDDING_NORMALIZATION, "l2");
        validator.validate(client, EmbeddingConfiguration.load());
    }

    @Test
    void acceptsOtherMatchingSimilaritiesWithEitherNormalization() throws Exception {
        for (String similarity : List.of("cosine", "euclidean", "maximum_inner_product")) {
            settings.set(JvmSettings.EMBEDDING_SIMILARITY, similarity);
            fieldType.put("similarityFunction", similarity);
            for (String normalization : List.of("none", "l2")) {
                settings.set(JvmSettings.EMBEDDING_NORMALIZATION, normalization);
                validator.validate(client, EmbeddingConfiguration.load());
            }
        }
    }

    @Test
    void rejectsMissingNullUnresolvedAndWrongDenseVectorClasses() throws Exception {
        for (Object value : new Object[]{null, "", " ", "solr.StrField", "DenseVectorField",
                "org.example.DenseVectorField", "${vector.class}", true, "private-schema-value"}) {
            fieldType.put("class", value);
            assertThrows(EmbeddingException.class, () -> validator.validate(client, EmbeddingConfiguration.load()));
        }
        fieldType.remove("class");
        assertFailureContains("class");
    }

    @Test
    void rejectsMissingNullBlankUnresolvedAndNonStringFieldTypesBeforeTypeLookup() throws Exception {
        for (Object value : new Object[]{null, "", " ", "${vector.type}", true, 7}) {
            requests.clear();
            field.put("type", value);
            assertFailureContains("type");
            assertEquals(1, requests.size());
        }
        field.remove("type");
        assertFailureContains("type");
    }

    @Test
    void requiresExplicitEffectiveSingleValuedIndexedNotStoredFlags() throws Exception {
        for (String key : List.of("multiValued", "indexed", "stored")) {
            Object expected = field.get(key);
            boolean wrong = !Boolean.TRUE.equals(expected);
            for (Object value : new Object[]{null, wrong, Boolean.toString(wrong), "", "TRUE", "FALSE",
                    "${field.flag}", 0, 1, "private-schema-value"}) {
                field.put(key, value);
                assertFailureContains(key);
            }
            field.remove(key);
            assertFailureContains(key);
            field.put(key, expected);
        }
    }

    @Test
    void effectiveFieldFlagsTakePrecedenceOverFieldTypeDefaults() throws Exception {
        fieldType.put("multiValued", true);
        fieldType.put("indexed", false);
        fieldType.put("stored", true);
        validator.validate(client, EmbeddingConfiguration.load());
    }

    @Test
    void neverAddsEmbedderCredentialsToSchemaRequests() throws Exception {
        settings.set(JvmSettings.EMBEDDING_AUTH_MODE, "bearer");
        settings.set(JvmSettings.EMBEDDING_AUTH_TOKEN, "private-embedder-token");
        validator.validate(client, EmbeddingConfiguration.load());
        for (SolrRequest<?> request : requests) {
            assertTrue(request.getHeaders() == null || request.getHeaders().isEmpty());
            assertNull(request.getBasicAuthUser());
            assertNull(request.getBasicAuthPassword());
            assertTrue(request.getParams() == null
                    || !request.getParams().toString().contains("private-embedder-token"));
        }
    }

    @Test
    void sanitizesTransportAndServerErrorsAtEitherSchemaRequest() throws Exception {
        String secret = "https://user:secret@solr.invalid/core private-schema-value";
        for (Exception failure : List.of(new IOException(secret), new SolrServerException(secret),
                new IllegalArgumentException(secret))) {
            for (boolean failOnType : List.of(false, true)) {
                SolrClient failing = mock(SolrClient.class);
                doAnswer(invocation -> {
                    if (failOnType && invocation.getArgument(0) instanceof SchemaRequest.Field) {
                        return response("field", field);
                    }
                    throw failure;
                }).when(failing).request(any(SolrRequest.class), isNull());
                EmbeddingException result = assertThrows(EmbeddingException.class,
                        () -> validator.validate(failing, EmbeddingConfiguration.load()));
                assertSafe(result);
            }
        }
    }

    @Test
    void rejectsMalformedAndMissingSchemaResponses() throws Exception {
        for (String key : List.of("field", "fieldType")) {
            for (Object value : new Object[]{null, "private-schema-value", new NamedList<>()}) {
                SolrClient malformed = mock(SolrClient.class);
                doAnswer(invocation -> {
                    if (key.equals("fieldType") && invocation.getArgument(0) instanceof SchemaRequest.Field) {
                        return response("field", field);
                    }
                    NamedList<Object> result = new NamedList<>();
                    result.add(key, value);
                    return result;
                }).when(malformed).request(any(SolrRequest.class), isNull());
                assertSafe(assertThrows(EmbeddingException.class,
                        () -> validator.validate(malformed, EmbeddingConfiguration.load())));
            }
        }
    }

    @Test
    void rejectsMissingArgumentsWithoutNetworkRequests() throws Exception {
        EmbeddingConfiguration config = EmbeddingConfiguration.load();
        assertThrows(EmbeddingException.class, () -> validator.validate((SolrClient) null, config));
        assertThrows(EmbeddingException.class, () -> validator.validate(client, null));
        assertThrows(EmbeddingException.class, () -> validator.validate((String) null, config));
        assertThrows(EmbeddingException.class, () -> validator.validate(" ", config));
        assertSafe(assertThrows(EmbeddingException.class,
                () -> validator.validate("https://user:secret@solr.invalid/core", null)));
        verifyNoInteractions(client);
    }

    private void assertFailureContains(String attribute) throws Exception {
        EmbeddingException failure = assertThrows(EmbeddingException.class,
                () -> validator.validate(client, EmbeddingConfiguration.load()));
        assertTrue(failure.getMessage().contains(attribute));
        assertSafe(failure);
    }

    private static void assertSafe(EmbeddingException failure) {
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        for (String secret : List.of("https://", "solr.invalid", "secret", "private-schema-value",
                "private-embedder-token", "${")) {
            assertFalse(failure.getMessage().contains(secret));
        }
    }

    private static NamedList<Object> response(String key, Map<String, Object> attributes) {
        NamedList<Object> values = new NamedList<>();
        attributes.forEach(values::add);
        NamedList<Object> result = new NamedList<>();
        result.add(key, values);
        return result;
    }
}

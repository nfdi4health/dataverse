package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.ArgumentMatchers;
import static edu.harvard.iq.dataverse.search.EmbeddingConfigurationTest.DIGEST;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@LocalJvmSettings
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class QueryEmbeddingTransportTest {

    private static final String SEARCH_FLAG = "dataverse.feature.semantic-search";
    private static final String GENERATION_FLAG = "dataverse.feature.semantic-embeddings";
    private static final String SOLR_URL = "http://solr.invalid/solr/collection1";
    private static final String ENDPOINT = "https://secret.example/private-proxy/v1/embeddings";
    private static final String QUERY = "private query payload";
    private static final String TOKEN = "private-token";
    private static final String MODEL = "qwen3-embedding:8b";

    private final Map<String, String> previousProperties = new LinkedHashMap<>();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<SolrRequest<?>> schemaRequests = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Deque<CompletableFuture<HttpResponse<String>>> replies = new ArrayDeque<>();
    private EmbeddingConfigurationTest.Settings settings;
    private EmbeddingClient client;
    private HttpClient transport;
    private SolrClient solr;
    private Map<String, Object> fieldType;
    private QueryEmbeddingService service;

    @BeforeEach
    void configure() throws Exception {
        settings = new EmbeddingConfigurationTest.Settings();
        settings.openAi();
        settings.set(JvmSettings.EMBEDDING_ENDPOINT, ENDPOINT);
        settings.set(JvmSettings.EMBEDDING_DIMENSIONS, "3");
        settings.set(JvmSettings.EMBEDDING_AUTH_MODE, "bearer");
        settings.set(JvmSettings.EMBEDDING_AUTH_TOKEN, TOKEN);
        settings.set(JvmSettings.EMBEDDING_QUERY_PREFIX_MODE, "none");
        setProperty(SEARCH_FLAG, "true");
        setProperty(GENERATION_FLAG, "false");
        setProperty(JvmSettings.SEARCH_SEMANTIC_TOP_K.getScopedKey(), "7");

        transport = mock(HttpClient.class);
        doAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            requests.add(request);
            events.add(request.method() + " " + request.uri().getRawPath());
            assertFalse(replies.isEmpty(), "Unexpected embedding transport request");
            return replies.removeFirst();
        }).when(transport).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        client = new EmbeddingClient(transport);

        Map<String, Object> field = Map.of(
                "name", SearchFields.DATASET_EMBEDDING, "type", "active_query_vector",
                "multiValued", false, "indexed", true, "stored", false);
        fieldType = new LinkedHashMap<>(Map.of(
                "name", "active_query_vector", "class", "solr.DenseVectorField",
                "vectorDimension", 3, "similarityFunction", "cosine", "vectorEncoding", "FLOAT32"));
        solr = mock(SolrClient.class);
        doAnswer(invocation -> {
            SolrRequest<?> request = invocation.getArgument(0);
            schemaRequests.add(request);
            assertTrue(request.getParams().getBool("showDefaults", false));
            if (request instanceof SchemaRequest.Field) {
                events.add("field");
                assertEquals("/schema/fields/" + SearchFields.DATASET_EMBEDDING, request.getPath());
                return schemaResponse("field", field);
            }
            events.add("fieldType");
            assertInstanceOf(SchemaRequest.FieldType.class, request);
            assertEquals("/schema/fieldtypes/active_query_vector", request.getPath());
            return schemaResponse("fieldType", fieldType);
        }).when(solr).request(any(SolrRequest.class), isNull());

        service = new QueryEmbeddingService();
        service.client = client;
        service.processor = new BasicDocumentEmbeddingProcessor();
        service.schemaValidator = new EmbeddingSchemaValidator() {
            @Override
            public void validate(String solrUrl, EmbeddingConfiguration configuration) throws EmbeddingException {
                assertEquals(SOLR_URL, solrUrl);
                // Exercise production schema checks without constructing a network client.
                super.validate(solr, configuration);
            }
        };
    }

    @AfterEach
    void restore() {
        try {
            if (client != null) {
                client.destroy();
            }
        } finally {
            try {
                if (settings != null) {
                    settings.close();
                }
            } finally {
                previousProperties.forEach((key, value) -> {
                    if (value == null) {
                        System.clearProperty(key);
                    } else {
                        System.setProperty(key, value);
                    }
                });
            }
        }
    }

    @Test
    void openAiReturnsTransportVectorAndIdentityAfterActiveSchemaValidation() throws Exception {
        reply(200, openAi("[3,4,0]"));

        QueryEmbeddingService.Result result = service.embed(QUERY, SOLR_URL);

        assertEquals(List.of(3f, 4f, 0f), result.vector());
        assertEquals(MODEL, result.modelIdentity());
        assertEquals(new BasicDocumentEmbeddingProcessor().pipelineIdentifier(EmbeddingConfiguration.load()),
                result.pipelineIdentifier());
        assertEquals(7, result.topK());
        assertEquals(List.of("field", "fieldType", "POST /private-proxy/v1/embeddings"), events);
        assertEquals(1, requests.size());
        HttpRequest request = requests.get(0);
        assertEquals(URI.create(ENDPOINT), request.uri());
        assertEquals("Bearer " + TOKEN, request.headers().firstValue("Authorization").orElseThrow());
        JsonObject payload = body(request);
        assertEquals(QUERY, payload.getString("input"));
        assertEquals(MODEL, payload.getString("model"));
        assertEquals("float", payload.getString("encoding_format"));
        assertEquals(3, payload.size());
        for (SolrRequest<?> schemaRequest : schemaRequests) {
            assertTrue(schemaRequest.getHeaders() == null || schemaRequest.getHeaders().isEmpty());
            assertNull(schemaRequest.getBasicAuthUser());
            assertNull(schemaRequest.getBasicAuthPassword());
            assertFalse(schemaRequest.getParams().toString().contains(TOKEN));
        }
        verify(solr, never()).close();
    }

    @Test
    void ollamaReturnsRevisionAttestedIdentityAfterBothInventoryChecks() throws Exception {
        ollama();
        reply(200, tags(DIGEST));
        reply(200, ollamaResponse("[[3,4,0]]"));
        reply(200, tags(DIGEST));

        QueryEmbeddingService.Result result = service.embed(QUERY, SOLR_URL);

        assertEquals(List.of(3f, 4f, 0f), result.vector());
        assertEquals(MODEL + "@" + DIGEST, result.modelIdentity());
        assertEquals(7, result.topK());
        assertEquals(List.of("field", "fieldType", "GET /private-proxy/api/tags",
                "POST /private-proxy/api/embed", "GET /private-proxy/api/tags"), events);
        JsonObject payload = body(requests.get(1));
        assertEquals(QUERY, payload.getString("input"));
        assertEquals(MODEL, payload.getString("model"));
        assertFalse(payload.getBoolean("truncate"));
        assertEquals(3, payload.size());
        for (HttpRequest request : requests) {
            assertEquals("Bearer " + TOKEN, request.headers().firstValue("Authorization").orElseThrow());
        }
    }

    @Test
    void activeSchemaMismatchPreventsEvenOllamaInventoryTransport() throws Exception {
        ollama();
        for (Map.Entry<String, Object> mismatch : Map.<String, Object>of(
                "vectorDimension", 4, "similarityFunction", "euclidean", "vectorEncoding", "BYTE").entrySet()) {
            Object original = fieldType.put(mismatch.getKey(), mismatch.getValue());
            EmbeddingException failure = assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL));
            assertTrue(failure.getMessage().contains(mismatch.getKey()));
            assertSafe(failure);
            fieldType.put(mismatch.getKey(), original);
        }
        assertEquals(6, schemaRequests.size());
        assertTrue(requests.isEmpty());
        verifyNoInteractions(transport);
    }

    @Test
    void rechecksActiveSchemaBeforeEachQueryInsteadOfReusingAnEarlierSuccess() throws Exception {
        reply(200, openAi("[1,2,3]"));
        service.embed(QUERY, SOLR_URL);
        fieldType.put("vectorDimension", 4096);

        EmbeddingException failure = assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL));

        assertTrue(failure.getMessage().contains("vectorDimension"));
        assertEquals(List.of("field", "fieldType", "POST /private-proxy/v1/embeddings", "field", "fieldType"), events);
        assertEquals(1, requests.size());
    }

    @Test
    void returnsTheClientsStablyL2NormalizedVector() throws Exception {
        settings.set(JvmSettings.EMBEDDING_NORMALIZATION, "l2");
        settings.set(JvmSettings.EMBEDDING_SIMILARITY, "dot_product");
        fieldType.put("similarityFunction", "dot_product");
        for (String vector : List.of("[3,4,0]", "[3e30,4e30,0]", "[3e-300,4e-300,0]")) {
            reply(200, openAi(vector));
            QueryEmbeddingService.Result result = service.embed(QUERY, SOLR_URL);
            assertEquals(0.6f, result.vector().get(0), 0.000001f);
            assertEquals(0.8f, result.vector().get(1), 0.000001f);
            assertEquals(0f, result.vector().get(2));
            assertEquals(MODEL, result.modelIdentity());
        }
        assertEquals(3, requests.size());
        assertEquals(6, schemaRequests.size());
    }

    @Test
    void rejectsMalformedOpenAiResponseShapesThroughTheService() {
        for (String response : List.of("{}", "[]", "not JSON",
                "{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}",
                "{\"model\":\"" + MODEL + "\",\"data\":[]}",
                "{\"model\":\"" + MODEL + "\",\"data\":[null]}",
                openAi("[1,2,3]").replace("\"index\":0,", ""),
                openAi("[1,2,3]").replace("\"index\":0", "\"index\":1"),
                openAi("[1,2,3]").replace("\"index\":0", "\"index\":0.5"),
                openAi("[1,2,3]").replace("\"index\":0", "\"index\":\"0\""),
                "{\"model\":\"" + MODEL + "\",\"data\":[{\"index\":0,\"embedding\":[1,2,3]},"
                        + "{\"index\":1,\"embedding\":[1,2,3]}]}")) {
            int before = requests.size();
            reply(200, response);
            assertSafe(assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL)));
            assertEquals(before + 1, requests.size());
        }
    }

    @Test
    void rejectsInvalidComponentsDimensionsAndCosineZeroVectorsThroughTheService() {
        for (String vector : List.of("null", "{}", "[]", "[1,2]", "[1,2,3,4]", "[1,null,3]",
                "[1,\"2\",3]", "[true,2,3]", "[1e400,2,3]", "[3.5e38,2,3]", "[NaN,2,3]",
                "[Infinity,2,3]", "[0,0,0]", "[1e-300,0,0]")) {
            int before = requests.size();
            reply(200, openAi(vector));
            assertSafe(assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL)));
            assertEquals(before + 1, requests.size());
        }
    }

    @Test
    void rejectsMalformedOllamaVectorsWithoutAcceptingOrAttestingAResult() {
        ollama();
        for (String embeddings : List.of("null", "{}", "[]", "[1,2,3]", "[[1,2,3],[1,2,3]]",
                "[[]]", "[[1,2]]", "[[1,null,3]]", "[[1,\"2\",3]]", "[[1e400,2,3]]")) {
            int before = requests.size();
            reply(200, tags(DIGEST));
            reply(200, ollamaResponse(embeddings));
            assertSafe(assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL)));
            assertEquals(before + 2, requests.size());
            assertEquals("POST", requests.get(requests.size() - 1).method());
        }
    }

    @Test
    void rejectsReturnedModelAliasesFromBothProtocols() {
        reply(200, openAi("[1,2,3]").replace(MODEL, "private-model-alias"));
        EmbeddingException openAiFailure = assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL));
        assertSafe(openAiFailure);
        assertFalse(openAiFailure.getMessage().contains("private-model-alias"));
        assertEquals(1, requests.size());

        ollama();
        reply(200, tags(DIGEST));
        reply(200, ollamaResponse("[[1,2,3]]").replace(MODEL, "private-model-alias"));
        EmbeddingException ollamaFailure = assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL));
        assertSafe(ollamaFailure);
        assertFalse(ollamaFailure.getMessage().contains("private-model-alias"));
        assertEquals(3, requests.size());
    }

    @Test
    void rejectsOllamaRevisionMismatchBeforeSendingTheQuery() {
        ollama();
        reply(200, tags("sha256:" + "b".repeat(64)));

        assertSafe(assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL)));

        assertEquals(List.of("field", "fieldType", "GET /private-proxy/api/tags"), events);
        assertEquals(1, requests.size());
    }

    @Test
    void rejectsOllamaRevisionChangeAfterEmbeddingRatherThanReturningTheVector() {
        ollama();
        reply(200, tags(DIGEST));
        reply(200, ollamaResponse("[[1,2,3]]"));
        reply(200, tags("sha256:" + "b".repeat(64)));

        assertSafe(assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL)));

        assertEquals(List.of("GET", "POST", "GET"), requests.stream().map(HttpRequest::method).toList());
        assertEquals(2, schemaRequests.size());
    }

    @Test
    void sanitizesHttpErrorsAndMalformedPayloadsWithoutRetainingSecrets() {
        String sensitive = QUERY + " " + TOKEN + " " + ENDPOINT;
        for (int status : List.of(301, 307, 401, 429, 500)) {
            reply(status, sensitive);
            EmbeddingException failure = assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL));
            assertTrue(failure.getMessage().contains(Integer.toString(status)));
            assertSafe(failure);
        }
        reply(200, sensitive);
        assertSafe(assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL)));
        reply(200, openAi("[1,\"" + sensitive + "\",3]"));
        assertSafe(assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL)));
        assertEquals(7, requests.size());
    }

    @Test
    void sanitizesAsynchronousTransportFailuresAndTheirCauses() {
        IOException cause = new IOException(QUERY + " " + TOKEN + " " + ENDPOINT);
        cause.addSuppressed(new IllegalStateException(TOKEN));
        replies.add(CompletableFuture.failedFuture(cause));

        assertSafe(assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL)));

        assertEquals(1, requests.size());
        assertEquals(2, schemaRequests.size());
    }

    @Test
    void sanitizesSynchronousTransportExceptions() {
        doThrow(new IllegalArgumentException(QUERY + " " + TOKEN + " " + ENDPOINT))
                .when(transport).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());

        assertSafe(assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL)));

        assertEquals(2, schemaRequests.size());
        verify(transport).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void sanitizesSolrTransportFailureBeforeAnyEmbeddingTransport() throws Exception {
        doThrow(new IOException(SOLR_URL + " " + QUERY + " " + TOKEN))
                .when(solr).request(any(SolrRequest.class), isNull());

        assertSafe(assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL)));

        verifyNoInteractions(transport);
    }

    @Test
    void rejectsUnsafeEndpointWithoutExposingItOrMakingAnyTransportRequest() {
        settings.set(JvmSettings.EMBEDDING_ENDPOINT, "https://user:" + TOKEN + "@secret.example/v1/embeddings");

        assertSafe(assertThrows(EmbeddingException.class, () -> service.embed(QUERY, SOLR_URL)));

        verifyNoInteractions(transport, solr);
    }

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private void ollama() {
        settings.set(JvmSettings.EMBEDDING_PROTOCOL, "ollama");
        settings.set(JvmSettings.EMBEDDING_ENDPOINT, "https://secret.example/private-proxy/api/embed");
        settings.set(JvmSettings.EMBEDDING_MODEL_REVISION, DIGEST);
    }

    private static String openAi(String vector) {
        return "{\"model\":\"" + MODEL + "\",\"data\":[{\"index\":0,\"embedding\":" + vector + "}]}";
    }

    private static String ollamaResponse(String embeddings) {
        return "{\"model\":\"" + MODEL + "\",\"embeddings\":" + embeddings + "}";
    }

    private static String tags(String digest) {
        return "{\"models\":[{\"name\":\"" + MODEL + "\",\"digest\":\"" + digest + "\"}]}";
    }

    @SuppressWarnings("unchecked")
    private void reply(int status, String payload) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(payload);
        replies.add(CompletableFuture.completedFuture(response));
    }

    private static void assertSafe(EmbeddingException failure) {
        assertNotNull(failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        for (String secret : List.of(QUERY, TOKEN, ENDPOINT, "secret.example", "private-proxy", SOLR_URL,
                "solr.invalid", "https://", "http://", "user:")) {
            assertFalse(failure.getMessage().contains(secret), "Failure must not expose " + secret);
        }
    }

    private static NamedList<Object> schemaResponse(String key, Map<String, Object> attributes) {
        NamedList<Object> values = new NamedList<>();
        attributes.forEach(values::add);
        NamedList<Object> result = new NamedList<>();
        result.add(key, values);
        return result;
    }

    private static JsonObject body(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<Void> complete = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable failure) {
                complete.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                complete.complete(null);
            }
        });
        complete.get(2, TimeUnit.SECONDS);
        try (var reader = Json.createReader(new StringReader(bytes.toString(StandardCharsets.UTF_8)))) {
            return reader.readObject();
        }
    }
}

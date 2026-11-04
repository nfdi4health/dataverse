package edu.harvard.iq.dataverse.search;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.ArgumentMatchers;
import static edu.harvard.iq.dataverse.search.EmbeddingConfigurationTest.DIGEST;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@LocalJvmSettings
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class EmbeddingClientTest {

    private EmbeddingConfigurationTest.Settings settings;
    private final List<HttpRequest> requests = new ArrayList<>();
    private final Deque<CompletableFuture<HttpResponse<String>>> replies = new ArrayDeque<>();
    private EmbeddingClient client;
    private HttpClient mockedTransport;
    private final List<EmbeddingClient> productionClients = new ArrayList<>();

    @BeforeEach
    void configure() {
        settings = new EmbeddingConfigurationTest.Settings();
        settings.openAi();
        settings.set(JvmSettings.EMBEDDING_DIMENSIONS, "3");
        mockedTransport = mock(HttpClient.class);
        doAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return replies.removeFirst();
        }).when(mockedTransport).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        client = new EmbeddingClient(mockedTransport);
    }

    @AfterEach
    void restore() {
        try {
            productionClients.forEach(EmbeddingClient::destroy);
            client.destroy();
        } finally {
            settings.close();
        }
    }

    @Test
    void openAiSendsExactModelFloatEncodingAndNoProjection() throws Exception {
        reply(200, openAi("[3,4,0]"));
        EmbeddingClient.Result result = client.embed("private input 😀", EmbeddingConfiguration.load());
        assertEquals(List.of(3f, 4f, 0f), result.vector());
        assertEquals("qwen3-embedding:8b", result.modelIdentity());
        assertThrows(UnsupportedOperationException.class, () -> result.vector().set(0, 1f));
        assertEquals(1, requests.size());
        HttpRequest request = requests.get(0);
        JsonObject payload = body(request);
        assertEquals("POST", request.method());
        assertEquals("/v1/embeddings", request.uri().getPath());
        assertEquals("qwen3-embedding:8b", payload.getString("model"));
        assertEquals("private input 😀", payload.getString("input"));
        assertEquals("float", payload.getString("encoding_format"));
        assertEquals(3, payload.size());
        assertFalse(payload.containsKey("dimensions"));
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(Duration.ofMillis(2000), request.timeout().orElseThrow());
    }

    @Test
    void ollamaChecksDigestOnBothSidesOfGenerationAndPreservesProxyPath() throws Exception {
        ollama();
        settings.set(JvmSettings.EMBEDDING_ENDPOINT, "http://localhost:11434/proxy%20path/api/embed");
        settings.set(JvmSettings.EMBEDDING_AUTH_MODE, "bearer");
        settings.set(JvmSettings.EMBEDDING_AUTH_TOKEN, "private-token");
        reply(200, tags(DIGEST, "name"));
        reply(200, "{\"model\":\"qwen3-embedding:8b\",\"embeddings\":[[3,4,0]]}");
        reply(200, tags(DIGEST, "model"));
        EmbeddingClient.Result result = client.embed("document", EmbeddingConfiguration.load());
        assertEquals("qwen3-embedding:8b@" + DIGEST, result.modelIdentity());
        assertEquals(List.of(3f, 4f, 0f), result.vector());
        assertEquals(List.of("GET", "POST", "GET"), requests.stream().map(HttpRequest::method).toList());
        assertEquals(List.of("/proxy%20path/api/tags", "/proxy%20path/api/embed", "/proxy%20path/api/tags"),
                requests.stream().map(r -> r.uri().getRawPath()).toList());
        for (HttpRequest request : requests) {
            assertEquals("Bearer private-token", request.headers().firstValue("Authorization").orElseThrow());
        }
        JsonObject payload = body(requests.get(1));
        assertEquals("qwen3-embedding:8b", payload.getString("model"));
        assertEquals("document", payload.getString("input"));
        assertFalse(payload.getBoolean("truncate"));
        assertEquals(3, payload.size());
    }

    @Test
    void ollamaRejectsDigestMismatchBeforeSendingText() throws Exception {
        ollama();
        reply(200, tags("sha256:" + "b".repeat(64), "name"));
        assertThrows(EmbeddingException.class, () -> client.embed("private input", EmbeddingConfiguration.load()));
        assertEquals(1, requests.size());
        assertEquals("GET", requests.get(0).method());
    }

    @Test
    void ollamaRejectsRevisionChangeAfterGeneration() throws Exception {
        ollama();
        reply(200, tags(DIGEST, "name"));
        reply(200, "{\"model\":\"qwen3-embedding:8b\",\"embeddings\":[[1,2,3]]}");
        reply(200, tags("sha256:" + "b".repeat(64), "model"));
        assertThrows(EmbeddingException.class, () -> client.embed("document", EmbeddingConfiguration.load()));
        assertEquals(3, requests.size());
    }

    @Test
    void ollamaRejectsMissingAndMalformedInventoryEntries() throws Exception {
        ollama();
        for (String inventory : List.of("{}", "{\"models\":[]}", "{\"models\":[null]}",
                "{\"models\":[{\"name\":\"other\",\"digest\":\"" + DIGEST + "\"}]}",
                "{\"models\":[{\"name\":\"qwen3-embedding:8b\"}]}")) {
            reply(200, inventory);
            assertThrows(EmbeddingException.class, () -> client.embed("document", EmbeddingConfiguration.load()));
        }
    }

    @Test
    void bothProtocolsRejectReturnedModelAliases() throws Exception {
        reply(200, openAi("[1,2,3]").replace("qwen3-embedding:8b", "alias"));
        assertThrows(EmbeddingException.class, () -> client.embed("document", EmbeddingConfiguration.load()));
        ollama();
        reply(200, tags(DIGEST, "name"));
        reply(200, "{\"model\":\"alias\",\"embeddings\":[[1,2,3]]}");
        assertThrows(EmbeddingException.class, () -> client.embed("document", EmbeddingConfiguration.load()));
    }

    @Test
    void rejectsInvalidOpenAiShapesAndMissingModel() throws Exception {
        String model = "\"model\":\"qwen3-embedding:8b\",";
        for (String response : List.of("{}", "[]", "not JSON", "{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}",
                "{" + model + "\"data\":[]}",
                "{" + model + "\"data\":[null]}",
                "{" + model + "\"data\":[{\"embedding\":[1,2,3]}]}",
                "{" + model + "\"data\":[{\"index\":1,\"embedding\":[1,2,3]}]}",
                "{" + model + "\"data\":[{\"index\":0.5,\"embedding\":[1,2,3]}]}",
                "{" + model + "\"data\":[{\"index\":\"0\",\"embedding\":[1,2,3]}]}",
                "{" + model + "\"data\":[{\"index\":0,\"embedding\":[1,2,3]},{\"index\":1,\"embedding\":[1,2,3]}]}")) {
            reply(200, response);
            assertThrows(EmbeddingException.class, () -> client.embed("document", EmbeddingConfiguration.load()), response);
        }
    }

    @Test
    void rejectsMissingEmptyNonnumericNonfiniteAndWrongDimensionVectors() throws Exception {
        for (String vector : List.of("null", "{}", "[]", "[1,2]", "[1,2,3,4]", "[1,null,3]",
                "[1,\"2\",3]", "[true,2,3]", "[1e400,2,3]", "[3.5e38,2,3]", "[NaN,2,3]", "[Infinity,2,3]")) {
            reply(200, openAi(vector));
            assertThrows(EmbeddingException.class, () -> client.embed("document", EmbeddingConfiguration.load()), vector);
        }
    }

    @Test
    void rejectsInvalidOllamaVectorShapes() throws Exception {
        ollama();
        for (String embeddings : List.of("null", "{}", "[]", "[1,2,3]", "[[1,2,3],[1,2,3]]", "[[]]",
                "[[1,2]]", "[[1,\"2\",3]]", "[[1e400,2,3]]")) {
            reply(200, tags(DIGEST, "name"));
            reply(200, "{\"model\":\"qwen3-embedding:8b\",\"embeddings\":" + embeddings + "}");
            assertThrows(EmbeddingException.class, () -> client.embed("document", EmbeddingConfiguration.load()));
        }
    }

    @Test
    void l2NormalizationUsesStableDoubleNorm() throws Exception {
        settings.set(JvmSettings.EMBEDDING_NORMALIZATION, "l2");
        for (String vector : List.of("[3,4,0]", "[3e30,4e30,0]", "[3e-30,4e-30,0]", "[3e-300,4e-300,0]")) {
            reply(200, openAi(vector));
            List<Float> result = client.embed("document", EmbeddingConfiguration.load()).vector();
            assertEquals(0.6f, result.get(0), 0.000001f);
            assertEquals(0.8f, result.get(1), 0.000001f);
            assertEquals(0f, result.get(2));
        }
        reply(200, openAi("[0,0,0]"));
        assertThrows(EmbeddingException.class, () -> client.embed("document", EmbeddingConfiguration.load()));
    }

    @Test
    void noNormalizationPreservesZeroAndNonunitVectorsForEuclideanSimilarity() throws Exception {
        settings.set(JvmSettings.EMBEDDING_SIMILARITY, "euclidean");
        reply(200, openAi("[0,0,0]"));
        assertEquals(List.of(0f, 0f, 0f), client.embed("document", EmbeddingConfiguration.load()).vector());
        reply(200, openAi("[-3,4,0]"));
        assertEquals(List.of(-3f, 4f, 0f), client.embed("document", EmbeddingConfiguration.load()).vector());
    }

    @Test
    void cosineRejectsZeroVectorsIncludingFloatUnderflow() throws Exception {
        for (String vector : List.of("[0,0,0]", "[1e-300,0,0]")) {
            reply(200, openAi(vector));
            assertThrows(EmbeddingException.class, () -> client.embed("document", EmbeddingConfiguration.load()));
        }
    }

    @Test
    void cosineRejectsFloatSquaredNormUnderflowAndOverflowUnlessL2Normalized() throws Exception {
        for (String vector : List.of("[1e-30,0,0]", "[1e30,0,0]")) {
            settings.set(JvmSettings.EMBEDDING_NORMALIZATION, "none");
            reply(200, openAi(vector));
            assertThrows(EmbeddingException.class, () -> client.embed("document", EmbeddingConfiguration.load()));
            settings.set(JvmSettings.EMBEDDING_NORMALIZATION, "l2");
            reply(200, openAi(vector));
            assertEquals(List.of(1f, 0f, 0f), client.embed("document", EmbeddingConfiguration.load()).vector());
        }
    }

    @Test
    void ollamaAcceptsInventoryDigestWithoutAlgorithmPrefix() throws Exception {
        ollama();
        reply(200, tags(DIGEST.substring("sha256:".length()), "name"));
        reply(200, "{\"model\":\"qwen3-embedding:8b\",\"embeddings\":[[3,4,0]]}");
        reply(200, tags(DIGEST.substring("sha256:".length()).toUpperCase(java.util.Locale.ROOT), "name"));
        assertEquals("qwen3-embedding:8b@" + DIGEST,
                client.embed("document", EmbeddingConfiguration.load()).modelIdentity());
    }

    @Test
    void httpFailuresAndMalformedResponsesNeverExposeSensitiveContent() throws Exception {
        for (int status : List.of(301, 307, 401, 429, 500)) {
            reply(status, "private input private-token https://secret.example/path");
            EmbeddingException failure = assertThrows(EmbeddingException.class,
                    () -> client.embed("private input", EmbeddingConfiguration.load()));
            assertTrue(failure.getMessage().contains(Integer.toString(status)));
            assertSafe(failure);
        }
        reply(200, "private input private-token https://secret.example/path");
        assertSafe(assertThrows(EmbeddingException.class,
                () -> client.embed("private input", EmbeddingConfiguration.load())));
        CompletableFuture<HttpResponse<String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalArgumentException("private input private-token https://secret.example/path"));
        replies.add(failed);
        assertSafe(assertThrows(EmbeddingException.class,
                () -> client.embed("private input", EmbeddingConfiguration.load())));
    }

    @Test
    void timeoutCancelsUnfinishedTransport() throws Exception {
        settings.set(JvmSettings.EMBEDDING_REQUEST_TIMEOUT_MS, "25");
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        replies.add(pending);
        EmbeddingException failure = assertThrows(EmbeddingException.class,
                () -> client.embed("document", EmbeddingConfiguration.load()));
        assertTrue(failure.getMessage().contains("timed out"));
        assertTrue(pending.isCancelled());
        assertSafe(failure);
    }

    @Test
    void interruptionIsPreservedAndTransportIsCancelled() throws Exception {
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        replies.add(pending);
        EmbeddingConfiguration config = EmbeddingConfiguration.load();
        Thread.currentThread().interrupt();
        try {
            EmbeddingException failure = assertThrows(EmbeddingException.class, () -> client.embed("document", config));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(pending.isCancelled());
            assertSafe(failure);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wholeRequestTimeoutIncludesAStalledResponseBody() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicBoolean headersSent = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                headersSent.set(true);
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            settings.set(JvmSettings.EMBEDDING_ENDPOINT,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings");
            settings.set(JvmSettings.EMBEDDING_REQUEST_TIMEOUT_MS, "1000");
            EmbeddingConfiguration config = EmbeddingConfiguration.load();
            EmbeddingClient production = productionClient();
            assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
                EmbeddingException failure = assertThrows(EmbeddingException.class,
                        () -> production.embed("document", config));
                assertTrue(headersSent.get(), "The server must send headers before the body timeout");
                assertSafe(failure);
            });
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }

    @Test
    void productionClientDoesNotFollowRedirects() throws Exception {
        AtomicBoolean redirected = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirected.set(true);
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            settings.set(JvmSettings.EMBEDDING_ENDPOINT,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings");
            EmbeddingClient production = productionClient();
            EmbeddingException failure = assertThrows(EmbeddingException.class,
                    () -> production.embed("document", EmbeddingConfiguration.load()));
            assertTrue(failure.getMessage().contains("307"));
            assertFalse(redirected.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNullOrEmptyInputBeforeTransport() throws Exception {
        EmbeddingConfiguration config = EmbeddingConfiguration.load();
        assertThrows(EmbeddingException.class, () -> client.embed(null, config));
        assertThrows(EmbeddingException.class, () -> client.embed("", config));
        assertThrows(EmbeddingException.class, () -> client.embed("document", null));
        assertTrue(requests.isEmpty());
    }

    @Test
    void transportIsReusedAndConnectTimeoutChangesRequireRestart() throws Exception {
        EmbeddingClient production = productionClient();
        EmbeddingConfiguration config = EmbeddingConfiguration.load();
        HttpClient first = production.transport(config);
        assertEquals(config.connectTimeout(), first.connectTimeout().orElseThrow());
        assertEquals(HttpClient.Redirect.NEVER, first.followRedirects());
        assertSame(first, production.transport(EmbeddingConfiguration.load()));
        settings.set(JvmSettings.EMBEDDING_REQUEST_TIMEOUT_MS, "3000");
        assertSame(first, production.transport(EmbeddingConfiguration.load()));
        settings.set(JvmSettings.EMBEDDING_CONNECT_TIMEOUT_MS, "2000");
        EmbeddingException failure = assertThrows(EmbeddingException.class,
                () -> production.embed("document", EmbeddingConfiguration.load()));
        assertTrue(failure.getMessage().contains("restart"));
        assertSafe(failure);
        settings.set(JvmSettings.EMBEDDING_CONNECT_TIMEOUT_MS, "1000");
        assertSame(first, production.transport(EmbeddingConfiguration.load()));
    }

    @Test
    void shutdownReleasesTransportExecutorAndPreventsReinitialization() throws Exception {
        EmbeddingClient production = productionClient();
        EmbeddingConfiguration config = EmbeddingConfiguration.load();
        HttpClient transport = production.transport(config);
        ExecutorService executor = (ExecutorService) transport.executor().orElseThrow();
        assertFalse(executor.isShutdown());
        production.destroy();
        assertTrue(executor.isShutdown());
        assertThrows(EmbeddingException.class, () -> production.transport(config));
        production.destroy();
    }

    @Test
    void shutdownCancelsInFlightRequests() throws Exception {
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            return pending;
        }).when(mockedTransport).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        EmbeddingConfiguration config = EmbeddingConfiguration.load();
        CompletableFuture<EmbeddingException> attempt = CompletableFuture.supplyAsync(() -> {
            try {
                client.embed("document", config);
                throw new AssertionError("Shutdown must cancel the unfinished request");
            } catch (EmbeddingException failure) {
                return failure;
            }
        });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            client.destroy();
            assertTrue(pending.isCancelled());
            assertSafe(attempt.get(2, TimeUnit.SECONDS));
        } finally {
            client.destroy();
        }
    }

    @Test
    void injectedTransportIsReusedAndReleasedWithoutClosingExternalResources() throws Exception {
        EmbeddingConfiguration config = EmbeddingConfiguration.load();
        assertSame(mockedTransport, client.transport(config));
        assertSame(mockedTransport, client.transport(config));
        client.destroy();
        assertThrows(EmbeddingException.class, () -> client.transport(config));
        verifyNoInteractions(mockedTransport);
    }

    @Test
    void byteLimitCancelsTheSubscriptionBeforeAcceptingAnOverflowingBufferBatch() throws Exception {
        settings.set(JvmSettings.EMBEDDING_MAX_RESPONSE_BYTES, "3");
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        doAnswer(invocation -> {
            HttpResponse.BodyHandler<String> handler = invocation.getArgument(1);
            HttpResponse.BodySubscriber<String> subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            subscriber.onSubscribe(subscription);
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2})));
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{3}), ByteBuffer.wrap(new byte[]{4})));
            subscriber.onComplete();
            return subscriber.getBody().<HttpResponse<String>>thenApply(body -> {
                throw new AssertionError("An overflowing body must not complete successfully");
            }).toCompletableFuture();
        }).when(mockedTransport).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        EmbeddingException failure = assertThrows(EmbeddingException.class,
                () -> client.embed("document", EmbeddingConfiguration.load()));
        assertTrue(failure.getMessage().contains("byte limit"));
        assertSafe(failure);
        verify(subscription).cancel();
    }

    @Test
    void chunkedEmbeddingAndErrorResponsesAreBoundedWithoutContentLength() throws Exception {
        byte[] oversized = "private input private-token https://secret.example/path ".repeat(100)
                .getBytes(StandardCharsets.UTF_8);
        for (int status : List.of(200, 500)) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/embeddings", exchange -> sendChunked(exchange, status, oversized));
            server.start();
            try {
                settings.set(JvmSettings.EMBEDDING_ENDPOINT,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings");
                settings.set(JvmSettings.EMBEDDING_MAX_RESPONSE_BYTES, "128");
                EmbeddingClient production = productionClient();
                EmbeddingException failure = assertThrows(EmbeddingException.class,
                        () -> production.embed("document", EmbeddingConfiguration.load()));
                assertTrue(failure.getMessage().contains("byte limit"));
                assertSafe(failure);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void chunkedOllamaInventoryIsBoundedBeforeAndAfterGeneration() throws Exception {
        ollama();
        settings.set(JvmSettings.EMBEDDING_MAX_RESPONSE_BYTES, "512");
        byte[] oversized = "private input private-token ".repeat(100).getBytes(StandardCharsets.UTF_8);
        for (boolean overflowBeforeGeneration : List.of(true, false)) {
            AtomicInteger inventoryRequests = new AtomicInteger();
            AtomicInteger generationRequests = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/tags", exchange -> {
                int request = inventoryRequests.incrementAndGet();
                byte[] response = overflowBeforeGeneration || request == 2
                        ? oversized : tags(DIGEST, "name").getBytes(StandardCharsets.UTF_8);
                sendChunked(exchange, 200, response);
            });
            server.createContext("/api/embed", exchange -> {
                generationRequests.incrementAndGet();
                sendChunked(exchange, 200,
                        "{\"model\":\"qwen3-embedding:8b\",\"embeddings\":[[1,2,3]]}".getBytes(StandardCharsets.UTF_8));
            });
            server.start();
            try {
                settings.set(JvmSettings.EMBEDDING_ENDPOINT,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/api/embed");
                EmbeddingClient production = productionClient();
                EmbeddingException failure = assertThrows(EmbeddingException.class,
                        () -> production.embed("document", EmbeddingConfiguration.load()));
                assertTrue(failure.getMessage().contains("byte limit"));
                assertSafe(failure);
                assertEquals(overflowBeforeGeneration ? 0 : 1, generationRequests.get());
                assertEquals(overflowBeforeGeneration ? 1 : 2, inventoryRequests.get());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void chunkedResponseLimitCountsUtf8BytesAndAcceptsTheExactBoundary() throws Exception {
        byte[] response = ("{\"note\":\"😀\"," + openAi("[1,2,3]").substring(1)).getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> sendChunked(exchange, 200, response));
        server.start();
        try {
            settings.set(JvmSettings.EMBEDDING_ENDPOINT,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings");
            settings.set(JvmSettings.EMBEDDING_MAX_RESPONSE_BYTES, Integer.toString(response.length));
            EmbeddingClient production = productionClient();
            assertEquals(List.of(1f, 2f, 3f), production.embed("document", EmbeddingConfiguration.load()).vector());
            settings.set(JvmSettings.EMBEDDING_MAX_RESPONSE_BYTES, Integer.toString(response.length - 1));
            EmbeddingException failure = assertThrows(EmbeddingException.class,
                    () -> production.embed("document", EmbeddingConfiguration.load()));
            assertTrue(failure.getMessage().contains("byte limit"));
            assertSafe(failure);
        } finally {
            server.stop(0);
        }
    }

    private EmbeddingClient productionClient() {
        EmbeddingClient production = new EmbeddingClient();
        productionClients.add(production);
        return production;
    }

    private static void sendChunked(HttpExchange exchange, int status, byte[] response) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, 0);
            int firstChunk = Math.min(16, response.length);
            exchange.getResponseBody().write(response, 0, firstChunk);
            exchange.getResponseBody().flush();
            exchange.getResponseBody().write(response, firstChunk, response.length - firstChunk);
        } finally {
            exchange.close();
        }
    }

    private void ollama() {
        settings.set(JvmSettings.EMBEDDING_PROTOCOL, "ollama");
        settings.set(JvmSettings.EMBEDDING_ENDPOINT, "http://localhost:11434/api/embed");
        settings.set(JvmSettings.EMBEDDING_MODEL_REVISION, DIGEST);
    }

    private static String openAi(String vector) {
        return "{\"model\":\"qwen3-embedding:8b\",\"data\":[{\"index\":0,\"embedding\":" + vector + "}]}";
    }

    private static String tags(String digest, String nameField) {
        return "{\"models\":[{\"" + nameField + "\":\"qwen3-embedding:8b\",\"digest\":\"" + digest + "\"}]}";
    }

    @SuppressWarnings("unchecked")
    private void reply(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        replies.add(CompletableFuture.completedFuture(response));
    }

    private static void assertSafe(EmbeddingException failure) {
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private input"));
        assertFalse(failure.getMessage().contains("private-token"));
        assertFalse(failure.getMessage().contains("secret.example"));
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

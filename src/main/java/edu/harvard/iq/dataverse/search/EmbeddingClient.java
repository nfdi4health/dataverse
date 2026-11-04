package edu.harvard.iq.dataverse.search;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Concrete Ollama and OpenAI-compatible client, with no redirects or provider-specific fallbacks. */
@ApplicationScoped
public class EmbeddingClient {

    private final boolean injectedTransport;
    private final Set<CompletableFuture<?>> pendingRequests = new HashSet<>();
    private HttpClient httpClient;
    private ExecutorService transportExecutor;
    private Duration transportConnectTimeout;
    private boolean destroyed;

    public EmbeddingClient() {
        injectedTransport = false;
    }

    // Only for deterministic transport tests; production always builds a no-redirect client.
    EmbeddingClient(HttpClient client) {
        injectedTransport = true;
        httpClient = Objects.requireNonNull(client);
    }

    synchronized HttpClient transport(EmbeddingConfiguration config) throws EmbeddingException {
        if (destroyed) {
            throw new EmbeddingException("Embedding transport has been shut down.");
        }
        if (transportConnectTimeout != null && !transportConnectTimeout.equals(config.connectTimeout())) {
            throw new EmbeddingException("Embedding connect timeout changed; restart is required.");
        }
        if (httpClient == null) {
            ExecutorService executor = Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "dataverse-embedding-http");
                thread.setDaemon(true);
                return thread;
            });
            try {
                httpClient = HttpClient.newBuilder().connectTimeout(config.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER).executor(executor).build();
                transportExecutor = executor;
            } catch (RuntimeException e) {
                executor.shutdownNow();
                throw new EmbeddingException("Unable to initialize embedding transport.");
            }
        }
        transportConnectTimeout = config.connectTimeout();
        return httpClient;
    }

    @PreDestroy
    void destroy() {
        Object client;
        ExecutorService executor;
        List<CompletableFuture<?>> pending;
        synchronized (this) {
            destroyed = true;
            client = injectedTransport ? null : httpClient;
            executor = transportExecutor;
            httpClient = null;
            transportExecutor = null;
            transportConnectTimeout = null;
            pending = List.copyOf(pendingRequests);
            pendingRequests.clear();
        }
        pending.forEach(request -> request.cancel(true));
        try {
            // HttpClient is not closeable on Java 17, but supports this lifecycle on Java 21.
            if (client instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Shutdown must not expose transport exceptions containing endpoint details.
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    public record Result(List<Float> vector, String modelIdentity) {
        public Result {
            vector = List.copyOf(vector);
            Objects.requireNonNull(modelIdentity);
        }
    }

    /**
     * Embeds already-processed text. Prefixing and Unicode input limits belong to the processor,
     * and the caller must validate dimensions/similarity against the live Solr field first.
     * HTTP is supported for local deployments; bearer credentials on HTTP travel unencrypted.
     * OpenAI-compatible results attest only to the returned model name, not a model revision;
     * deployments must independently make that endpoint/model immutable.
     */
    public Result embed(String text, EmbeddingConfiguration config) throws EmbeddingException {
        if (text == null || text.isEmpty() || config == null) {
            throw new EmbeddingException("Embedding requires nonempty text and configuration.");
        }
        try {
            HttpClient client = transport(config);
            boolean ollama = config.protocol().equals("ollama");
            if (ollama) {
                verifyRevision(client, config);
            }
            JsonObjectBuilder payload = Json.createObjectBuilder()
                    .add("model", config.model()).add("input", text);
            if (ollama) {
                payload.add("truncate", false);
            } else {
                payload.add("encoding_format", "float");
            }
            JsonObject response = exchange(client, request(config, config.endpoint())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.build().toString(), StandardCharsets.UTF_8))
                    .build(), config);
            String returnedModel = string(response, "model");
            if (!config.model().equals(returnedModel)) {
                throw new EmbeddingException("Embedding response model does not match the configured model.");
            }
            List<Float> vector = vector(response, config, ollama);
            if (ollama) {
                verifyRevision(client, config);
                return new Result(vector, config.model() + "@" + config.modelRevision().orElseThrow());
            }
            return new Result(vector, returnedModel);
        } catch (RuntimeException e) {
            throw new EmbeddingException("Invalid embedding request or response.");
        }
    }

    private static HttpRequest.Builder request(EmbeddingConfiguration config, URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(config.requestTimeout())
                .header("Accept", "application/json");
        if (config.authMode().equals("bearer")) {
            builder.header("Authorization", "Bearer " + config.authToken().orElseThrow());
        }
        return builder;
    }

    private JsonObject exchange(HttpClient client, HttpRequest request, EmbeddingConfiguration config)
            throws EmbeddingException {
        CompletableFuture<HttpResponse<String>> future = null;
        try {
            // Unlike an InputStream body handler, ofString completes only after the entire body.
            // get supplies a wall-clock bound even when headers arrive but the body stalls.
            synchronized (this) {
                if (destroyed) {
                    throw new EmbeddingException("Embedding transport has been shut down.");
                }
                future = client.sendAsync(request, info -> new BoundedBodySubscriber(config.maxResponseBytes()));
                pendingRequests.add(future);
            }
            HttpResponse<String> response = future.get(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new EmbeddingException("Embedding service returned HTTP status " + response.statusCode() + ".");
            }
            try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
                return reader.readObject();
            }
        } catch (InterruptedException e) {
            if (future != null) {
                future.cancel(true);
            }
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Embedding request interrupted.");
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            throw new EmbeddingException("Embedding request timed out.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ResponseLimitException) {
                throw new EmbeddingException("Embedding response exceeds the configured byte limit.");
            }
            throw new EmbeddingException("Embedding transport or response failure.");
        } catch (RuntimeException e) {
            throw new EmbeddingException("Embedding transport or response failure.");
        } finally {
            if (future != null) {
                synchronized (this) {
                    pendingRequests.remove(future);
                }
            }
        }
    }

    private static final class ResponseLimitException extends RuntimeException {
    }

    /** Counts raw bytes before forwarding any buffer batch to the accumulating string subscriber. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate =
                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private final int limit;
        private int received;
        private Flow.Subscription subscription;
        private boolean finished;

        private BoundedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<String> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (finished) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - received) {
                    finished = true;
                    subscription.cancel();
                    delegate.onError(new ResponseLimitException());
                    return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable failure) {
            if (!finished) {
                finished = true;
                delegate.onError(failure);
            }
        }

        @Override
        public void onComplete() {
            if (!finished) {
                finished = true;
                delegate.onComplete();
            }
        }
    }

    private void verifyRevision(HttpClient client, EmbeddingConfiguration config) throws EmbeddingException {
        // Replace only the final route, preserving reverse-proxy base paths and raw URI escaping.
        String endpoint = config.endpoint().toASCIIString();
        URI tags = URI.create(endpoint.substring(0, endpoint.length() - "/api/embed".length()) + "/api/tags");
        JsonObject response = exchange(client, request(config, tags).GET().build(), config);
        JsonArray models = array(response, "models");
        boolean found = false;
        for (JsonValue value : models) {
            if (!(value instanceof JsonObject model)) {
                throw new EmbeddingException("Invalid Ollama model inventory.");
            }
            if (config.model().equals(optionalString(model, "name"))
                    || config.model().equals(optionalString(model, "model"))) {
                String digest = string(model, "digest");
                if (!digest.matches("(?:sha256:)?[0-9a-fA-F]{64}")) {
                    throw new EmbeddingException("Ollama model inventory contains an invalid SHA-256 digest.");
                }
                String canonicalDigest = "sha256:" + digest.replaceFirst("^sha256:", "").toLowerCase(java.util.Locale.ROOT);
                if (!config.modelRevision().orElseThrow().equalsIgnoreCase(canonicalDigest)) {
                    throw new EmbeddingException("Ollama model revision does not match the configured digest.");
                }
                found = true;
            }
        }
        if (!found) {
            throw new EmbeddingException("Configured Ollama model is absent from the model inventory.");
        }
    }

    private static List<Float> vector(JsonObject response, EmbeddingConfiguration config, boolean ollama)
            throws EmbeddingException {
        JsonArray values;
        if (ollama) {
            JsonArray embeddings = array(response, "embeddings");
            if (embeddings.size() != 1 || !(embeddings.get(0) instanceof JsonArray)) {
                throw new EmbeddingException("Embedding response must contain exactly one vector.");
            }
            values = embeddings.getJsonArray(0);
        } else {
            JsonArray data = array(response, "data");
            if (data.size() != 1 || !(data.get(0) instanceof JsonObject item)
                    || !(item.get("index") instanceof JsonNumber index)
                    || index.bigDecimalValue().compareTo(java.math.BigDecimal.ZERO) != 0) {
                throw new EmbeddingException("Embedding response must contain exactly one vector at index zero.");
            }
            values = array(item, "embedding");
        }
        if (values.isEmpty() || values.size() != config.dimensions()) {
            throw new EmbeddingException("Embedding vector dimensions do not match configuration.");
        }
        double[] components = new double[values.size()];
        double norm = 0;
        for (int i = 0; i < values.size(); i++) {
            if (!(values.get(i) instanceof JsonNumber number)) {
                throw new EmbeddingException("Embedding vector contains a nonnumeric value.");
            }
            double component = number.doubleValue();
            if (!Double.isFinite(component) || !Float.isFinite((float) component)) {
                throw new EmbeddingException("Embedding vector contains a nonfinite or overflowing value.");
            }
            components[i] = component;
            norm = Math.hypot(norm, component);
        }
        boolean normalize = config.normalization().equals("l2");
        if (normalize && (norm == 0 || !Double.isFinite(norm))) {
            throw new EmbeddingException("Embedding vector cannot be L2 normalized.");
        }
        List<Float> vector = new ArrayList<>(values.size());
        boolean nonzero = false;
        float squaredNorm = 0;
        for (double component : components) {
            float value = (float) (normalize ? component / norm : component);
            if (!Float.isFinite(value)) {
                throw new EmbeddingException("Embedding vector is nonfinite after float conversion.");
            }
            nonzero |= value != 0f;
            squaredNorm += value * value;
            vector.add(value);
        }
        if (config.similarity().equals("cosine")) {
            if (!nonzero) {
                throw new EmbeddingException("Cosine similarity cannot index a zero embedding vector.");
            }
            if (squaredNorm == 0 || !Float.isFinite(squaredNorm)) {
                throw new EmbeddingException("Cosine similarity requires a finite, positive float squared norm.");
            }
        }
        return vector;
    }

    private static JsonArray array(JsonObject object, String key) throws EmbeddingException {
        if (object.get(key) instanceof JsonArray array) {
            return array;
        }
        throw new EmbeddingException("Embedding response is missing a required array.");
    }

    private static String string(JsonObject object, String key) throws EmbeddingException {
        String value = optionalString(object, key);
        if (value == null || value.isEmpty()) {
            throw new EmbeddingException("Embedding response is missing a required model identity.");
        }
        return value;
    }

    private static String optionalString(JsonObject object, String key) {
        return object.get(key) instanceof JsonString value ? value.getString() : null;
    }
}

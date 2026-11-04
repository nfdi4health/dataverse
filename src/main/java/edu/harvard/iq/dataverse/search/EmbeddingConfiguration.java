package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** An immutable, explicitly configured embedding pipeline; deliberately has no value-bearing toString. */
public final class EmbeddingConfiguration {

    private final URI endpoint;
    private final String protocol;
    private final String model;
    private final Optional<String> modelRevision;
    private final String authMode;
    private final Optional<String> authToken;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final int dimensions;
    private final String similarity;
    private final String normalization;
    private final String processor;
    private final String documentPrefix;
    private final int maxInputCodePoints;
    private final String inputLimitAction;

    private EmbeddingConfiguration() throws EmbeddingException {
        protocol = choice(JvmSettings.EMBEDDING_PROTOCOL, Set.of("ollama", "openai-compatible"));
        endpoint = endpoint(required(JvmSettings.EMBEDDING_ENDPOINT), protocol);
        model = required(JvmSettings.EMBEDDING_MODEL);
        modelRevision = optional(JvmSettings.EMBEDDING_MODEL_REVISION);
        if (protocol.equals("ollama")) {
            if (modelRevision.isEmpty() || !modelRevision.get().matches("sha256:[0-9a-fA-F]{64}")) {
                throw invalid(JvmSettings.EMBEDDING_MODEL_REVISION);
            }
        } else if (modelRevision.isPresent()) {
            throw new EmbeddingException("OpenAI-compatible embeddings provide no revision attestation; model-revision must be unset.");
        }
        authMode = choice(JvmSettings.EMBEDDING_AUTH_MODE, Set.of("none", "bearer"));
        authToken = optional(JvmSettings.EMBEDDING_AUTH_TOKEN);
        if (authMode.equals("bearer")) {
            if (authToken.isEmpty() || authToken.get().isEmpty()
                    || authToken.get().chars().anyMatch(c -> c <= 0x20 || c >= 0x7f)) {
                throw invalid(JvmSettings.EMBEDDING_AUTH_TOKEN);
            }
        } else if (authToken.isPresent()) {
            throw new EmbeddingException("Embedding auth-token must be unset when auth-mode is none.");
        }
        connectTimeout = Duration.ofMillis(positiveLong(JvmSettings.EMBEDDING_CONNECT_TIMEOUT_MS));
        requestTimeout = Duration.ofMillis(positiveLong(JvmSettings.EMBEDDING_REQUEST_TIMEOUT_MS));
        maxResponseBytes = positiveInt(JvmSettings.EMBEDDING_MAX_RESPONSE_BYTES);
        dimensions = positiveInt(JvmSettings.EMBEDDING_DIMENSIONS);
        similarity = choice(JvmSettings.EMBEDDING_SIMILARITY,
                Set.of("cosine", "dot_product", "euclidean", "maximum_inner_product"));
        normalization = choice(JvmSettings.EMBEDDING_NORMALIZATION, Set.of("none", "l2"));
        processor = choice(JvmSettings.EMBEDDING_PROCESSOR, Set.of("basic-v1"));
        String prefixMode = choice(JvmSettings.EMBEDDING_DOCUMENT_PREFIX_MODE, Set.of("none", "literal"));
        if (prefixMode.equals("none")) {
            documentPrefix = "";
        } else {
            Optional<String> prefix = optional(JvmSettings.EMBEDDING_DOCUMENT_PREFIX);
            if (prefix.isEmpty() || prefix.get().isEmpty()) {
                throw invalid(JvmSettings.EMBEDDING_DOCUMENT_PREFIX);
            }
            documentPrefix = prefix.get();
        }
        maxInputCodePoints = positiveInt(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS);
        inputLimitAction = choice(JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION, Set.of("reject", "truncate"));
    }

    /** Reads runtime settings without supplying deployment defaults. */
    public static EmbeddingConfiguration load() throws EmbeddingException {
        try {
            return new EmbeddingConfiguration();
        } catch (RuntimeException e) {
            // Configuration providers may include the offending value in their exceptions.
            throw new EmbeddingException("Unable to load embedding configuration.");
        }
    }

    private static Optional<String> optional(JvmSettings setting) throws EmbeddingException {
        try {
            return setting.lookupOptional();
        } catch (RuntimeException e) {
            throw invalid(setting);
        }
    }

    private static String required(JvmSettings setting) throws EmbeddingException {
        Optional<String> value = optional(setting);
        if (value.isEmpty() || value.get().isBlank()) {
            throw invalid(setting);
        }
        return value.get();
    }

    private static String choice(JvmSettings setting, Set<String> choices) throws EmbeddingException {
        String value = required(setting);
        if (!choices.contains(value)) {
            throw invalid(setting);
        }
        return value;
    }

    private static long positiveLong(JvmSettings setting) throws EmbeddingException {
        try {
            long value = Long.parseLong(required(setting));
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // Never include the invalid value or the original exception.
        }
        throw invalid(setting);
    }

    private static int positiveInt(JvmSettings setting) throws EmbeddingException {
        long value = positiveLong(setting);
        if (value > Integer.MAX_VALUE) {
            throw invalid(setting);
        }
        return (int) value;
    }

    private static URI endpoint(String value, String protocol) throws EmbeddingException {
        try {
            URI uri = URI.create(value);
            String suffix = protocol.equals("ollama") ? "/api/embed" : "/v1/embeddings";
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)
                    && uri.getRawPath() != null && uri.getRawPath().endsWith(suffix)) {
                return uri;
            }
        } catch (IllegalArgumentException e) {
            // URI syntax errors can contain credentials and the full URL.
        }
        throw invalid(JvmSettings.EMBEDDING_ENDPOINT);
    }

    private static EmbeddingException invalid(JvmSettings setting) {
        return new EmbeddingException("Missing or invalid embedding setting: " + setting.getScopedKey());
    }

    public URI endpoint() { return endpoint; }
    public String protocol() { return protocol; }
    public String model() { return model; }
    public Optional<String> modelRevision() { return modelRevision; }
    public String authMode() { return authMode; }
    public Optional<String> authToken() { return authToken; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration requestTimeout() { return requestTimeout; }
    public int maxResponseBytes() { return maxResponseBytes; }
    public int dimensions() { return dimensions; }
    public String similarity() { return similarity; }
    public String normalization() { return normalization; }
    public String processor() { return processor; }
    public String documentPrefix() { return documentPrefix; }
    public int maxInputCodePoints() { return maxInputCodePoints; }
    public String inputLimitAction() { return inputLimitAction; }
}

package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.settings.FeatureFlags;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;

/** Prepares natural-language queries for the configured document embedding deployment. */
@Dependent
public class QueryEmbeddingService {

    @Inject
    EmbeddingClient client;
    @Inject
    EmbeddingSchemaValidator schemaValidator;
    @Inject
    DocumentEmbeddingProcessor processor;

    /** Provenance for selecting compatible documents, with the fixed retrieval candidate window. */
    public record Result(List<Float> vector, String modelIdentity, String pipelineIdentifier, int topK) {
        public Result {
            vector = List.copyOf(vector);
            Objects.requireNonNull(modelIdentity);
            Objects.requireNonNull(pipelineIdentifier);
        }
    }

    /**
     * Loads query-only configuration on demand, without requiring document generation to be enabled.
     * Deployments must establish query/document prefix compatibility from the selected model's contract;
     * neither the model name nor the document pipeline identifier establishes that compatibility.
     */
    public Result embed(String query, String solrUrl) throws EmbeddingException {
        try {
            if (!FeatureFlags.SEMANTIC_SEARCH.enabled()) {
                throw new EmbeddingException("Semantic search is not enabled.");
            }
            if (query == null || query.isBlank()) {
                throw new EmbeddingException("Semantic search requires nonblank query text.");
            }
            QuerySettings settings = QuerySettings.load();
            EmbeddingConfiguration configuration = EmbeddingConfiguration.load();
            String text = prepare(query, settings.prefix(), configuration);
            schemaValidator.validate(solrUrl, configuration);
            EmbeddingClient.Result result = client.embed(text, configuration);
            return new Result(result.vector(), result.modelIdentity(),
                    processor.pipelineIdentifier(configuration), settings.topK());
        } catch (RuntimeException e) {
            // Configuration providers and collaborators can expose input, endpoints, or credentials.
            throw new EmbeddingException("Unable to embed semantic search query.");
        }
    }

    private static String prepare(String query, String prefix, EmbeddingConfiguration configuration)
            throws EmbeddingException {
        int remaining = configuration.maxInputCodePoints() - prefix.codePointCount(0, prefix.length());
        if (remaining <= 0) {
            throw new EmbeddingException("Embedding input limit leaves no room for query content after the query prefix.");
        }
        String content = query;
        if (query.codePointCount(0, query.length()) > remaining) {
            if (configuration.inputLimitAction().equals("reject")) {
                throw new EmbeddingException("Prepared query embedding input exceeds max-input-code-points.");
            }
            content = query.substring(0, query.offsetByCodePoints(0, remaining));
        }
        if (content.isBlank()) {
            throw new EmbeddingException("Embedding input limit leaves no usable query content.");
        }
        return prefix + content;
    }

    private record QuerySettings(String prefix, int topK) {
        private static QuerySettings load() throws EmbeddingException {
            String mode = JvmSettings.EMBEDDING_QUERY_PREFIX_MODE.lookupOptional().orElse("");
            String prefix;
            switch (mode) {
                case "none" -> prefix = "";
                case "literal" -> {
                    prefix = JvmSettings.EMBEDDING_QUERY_PREFIX.lookupOptional().orElse("");
                    if (prefix.isEmpty()) {
                        throw invalid(JvmSettings.EMBEDDING_QUERY_PREFIX);
                    }
                }
                default -> throw invalid(JvmSettings.EMBEDDING_QUERY_PREFIX_MODE);
            }
            try {
                int topK = Integer.parseInt(JvmSettings.SEARCH_SEMANTIC_TOP_K.lookupOptional().orElse(""));
                if (topK > 0) {
                    return new QuerySettings(prefix, topK);
                }
            } catch (NumberFormatException e) {
                // Do not retain the configured value or the original exception.
            }
            throw invalid(JvmSettings.SEARCH_SEMANTIC_TOP_K);
        }

        private static EmbeddingException invalid(JvmSettings setting) {
            return new EmbeddingException("Missing or invalid semantic search setting: " + setting.getScopedKey());
        }
    }
}

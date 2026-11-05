package edu.harvard.iq.dataverse.search;

import jakarta.enterprise.context.Dependent;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.params.ModifiableSolrParams;

/** Checks the active, resolved Solr schema without caching deployment assumptions. */
@Dependent
public class EmbeddingSchemaValidator {

    /** Uses only the Solr URL; embedder credentials must never be forwarded to Solr. */
    public void validate(String solrUrl, EmbeddingConfiguration config) throws EmbeddingException {
        if (solrUrl == null || solrUrl.isBlank() || config == null) {
            throw new EmbeddingException("Embedding schema validation requires a Solr URL and configuration.");
        }
        try (SolrClient client = new Http2SolrClient.Builder(solrUrl)
                .withConnectionTimeout(config.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .withRequestTimeout(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .withIdleTimeout(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .withFollowRedirects(false)
                .build()) {
            validate(client, config);
        } catch (EmbeddingException e) {
            // Do not retain any unsanitized close failure suppressed by try-with-resources.
            throw new EmbeddingException(e.getMessage());
        } catch (IOException | RuntimeException e) {
            throw new EmbeddingException("Unable to validate the active Solr embedding schema.");
        }
    }

    // The caller owns this client. Production uses the bounded, no-redirect client above.
    void validate(SolrClient client, EmbeddingConfiguration config) throws EmbeddingException {
        if (client == null || config == null) {
            throw new EmbeddingException("Embedding schema validation requires a Solr client and configuration.");
        }
        try {
            ModifiableSolrParams params = new ModifiableSolrParams().set("showDefaults", true);
            Map<String, Object> field = new SchemaRequest.Field(SearchFields.DATASET_EMBEDDING, params)
                    .process(client).getField();
            String type = resolvedString(field, "type");
            requireFlag(field, "multiValued", false);
            requireFlag(field, "indexed", true);
            requireFlag(field, "stored", false);

            Map<String, Object> attributes = new SchemaRequest.FieldType(type, params)
                    .process(client).getFieldType().getAttributes();
            String fieldClass = resolvedString(attributes, "class");
            if (!"solr.DenseVectorField".equals(fieldClass)
                    && !"org.apache.solr.schema.DenseVectorField".equals(fieldClass)) {
                throw new EmbeddingException("Solr embedding field must use DenseVectorField.");
            }
            if (!"FLOAT32".equals(resolvedString(attributes, "vectorEncoding"))) {
                throw new EmbeddingException("Solr embedding field requires explicit FLOAT32 vectorEncoding.");
            }
            int dimensions = positiveDimension(attributes);
            if (dimensions != config.dimensions()) {
                throw new EmbeddingException("Solr embedding vectorDimension does not match the configured dimensions.");
            }
            String similarity = resolvedString(attributes, "similarityFunction");
            if (!similarity.equals(config.similarity())) {
                throw new EmbeddingException("Solr embedding similarityFunction does not match the configured similarity.");
            }
            if ("dot_product".equals(similarity) && !"l2".equals(config.normalization())) {
                throw new EmbeddingException("Solr dot_product similarity requires explicit l2 embedding normalization.");
            }
        } catch (SolrServerException | IOException | RuntimeException e) {
            // Solr errors and malformed responses can contain URLs, credentials, and response bodies.
            throw new EmbeddingException("Unable to read a valid active Solr embedding schema.");
        }
    }

    private static String resolvedString(Map<String, Object> attributes, String key) throws EmbeddingException {
        Object value = attributes == null ? null : attributes.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.contains("${")) {
            throw new EmbeddingException("Missing, invalid, or unresolved Solr embedding schema attribute: " + key);
        }
        return text;
    }

    private static void requireFlag(Map<String, Object> field, String key, boolean expected)
            throws EmbeddingException {
        Object value = field == null ? null : field.get(key);
        if (!Boolean.valueOf(expected).equals(value) && !Boolean.toString(expected).equals(value)) {
            throw new EmbeddingException("Solr embedding field requires " + key + "=" + expected + ".");
        }
    }

    private static int positiveDimension(Map<String, Object> attributes) throws EmbeddingException {
        Object value = attributes == null ? null : attributes.get("vectorDimension");
        // Solr can return the resolved dimension as either a JSON integer or a string.
        if (value instanceof Number || value instanceof String) {
            String text = value.toString();
            if (text.matches("[0-9]+")) {
                try {
                    int dimension = Integer.parseInt(text);
                    if (dimension > 0) {
                        return dimension;
                    }
                } catch (NumberFormatException e) {
                    // Reject overflow without exposing the schema value.
                }
            }
        }
        throw new EmbeddingException("Solr embedding vectorDimension must be a resolved positive integer.");
    }
}

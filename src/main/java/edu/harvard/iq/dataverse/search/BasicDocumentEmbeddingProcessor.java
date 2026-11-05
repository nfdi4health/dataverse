package edu.harvard.iq.dataverse.search;

import jakarta.enterprise.context.Dependent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.apache.solr.common.SolrInputDocument;

/** The Basic concatenation contract, without its document-ID fallback. */
@Dependent
public class BasicDocumentEmbeddingProcessor implements DocumentEmbeddingProcessor {

    private static final List<String> SOURCE_FIELDS = List.of(
            "title", "name", "nameSort", "citation", "dsDescriptionValue",
            "description", "subject", "subjects", "authorName", "dsPersistentId");

    @Override
    public String prepare(SolrInputDocument document, EmbeddingConfiguration configuration) throws EmbeddingException {
        List<String> values = new ArrayList<>();
        for (String field : SOURCE_FIELDS) {
            append(values, document.getFieldValues(field));
        }
        if (values.isEmpty()) {
            return "";
        }
        String content = String.join("\n", values);
        String prefix = configuration.documentPrefix();
        String text = prefix + content;
        int limit = configuration.maxInputCodePoints();
        if (text.codePointCount(0, text.length()) <= limit) {
            return text;
        }
        if (configuration.inputLimitAction().equals("reject")) {
            throw new EmbeddingException("Prepared embedding input exceeds max-input-code-points.");
        }
        int remaining = limit - prefix.codePointCount(0, prefix.length());
        if (remaining <= 0) {
            throw new EmbeddingException("Embedding input limit leaves no room for metadata after the document prefix.");
        }
        String truncated = content.substring(0, content.offsetByCodePoints(0, remaining));
        if (truncated.isBlank()) {
            return "";
        }
        return prefix + truncated;
    }

    private static void append(List<String> values, Object value) {
        if (value instanceof Set<?> set) {
            // External vocabulary indexing may supply sets; never hash their iteration order.
            List<String> ordered = new ArrayList<>();
            set.forEach(item -> append(ordered, item));
            ordered.sort(String::compareTo);
            values.addAll(ordered);
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(item -> append(values, item));
        } else if (value != null && !value.toString().isBlank()) {
            values.add(value.toString());
        }
    }

    @Override
    public String pipelineIdentifier(EmbeddingConfiguration configuration) {
        // Length framing avoids ambiguous manifests when prefixes contain separators or newlines.
        List<String> contract = List.of("basic-v1", String.join(",", SOURCE_FIELDS),
                "LF;preserve-indexed-text;skip-isBlank;preserve-list-order;sort-sets;keep-duplicates;no-id-fallback",
                "unicode-code-points;preserve-prefix;no-server-truncation",
                configuration.protocol(), configuration.documentPrefix(),
                Integer.toString(configuration.maxInputCodePoints()), configuration.inputLimitAction(),
                configuration.normalization(), Integer.toString(configuration.dimensions()), configuration.similarity());
        StringBuilder manifest = new StringBuilder();
        for (String value : contract) {
            manifest.append(value.length()).append(':').append(value);
        }
        return "basic-v1-" + sha256(manifest.toString());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.");
        }
    }
}

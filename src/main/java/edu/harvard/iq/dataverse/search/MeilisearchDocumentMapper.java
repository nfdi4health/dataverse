package edu.harvard.iq.dataverse.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;

@ApplicationScoped
public class MeilisearchDocumentMapper {

    static final String SEMANTIC_TEXT_FIELD = "meiliSemanticText";
    private static final List<String> SEMANTIC_SOURCE_FIELDS = List.of(
            "title", "name", "nameSort", "citation", "dsDescriptionValue",
            "description", "subject", "subjects", "authorName", "dsPersistentId");

    public Map<String, Object> map(SolrInputDocument source) throws SearchException {
        if (source == null) {
            throw new SearchException("Cannot map a null search document", null);
        }
        Object id = source.getFieldValue(SearchFields.ID);
        if (id == null || id.toString().isBlank()) {
            throw new SearchException("Meilisearch documents require a non-empty id", null);
        }

        Map<String, Object> target = new LinkedHashMap<>();
        for (SolrInputField field : source) {
            if ("_version_".equals(field.getName())) {
                continue;
            }
            Collection<Object> fieldValues = field.getValues();
            if (fieldValues == null || fieldValues.isEmpty()) {
                continue;
            }
            List<Object> values = fieldValues.stream()
                    .filter(value -> value != null)
                    .map(this::normalize)
                    .toList();
            if (values.isEmpty()) {
                continue;
            }
            target.put(field.getName(), values.size() == 1 ? values.getFirst() : new ArrayList<>(values));
        }
        target.put(SearchFields.ID, id.toString());
        target.put(SEMANTIC_TEXT_FIELD, semanticText(target, id.toString()));
        return target;
    }

    public List<Map<String, Object>> mapAll(Collection<SolrInputDocument> documents) throws SearchException {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> mapped = new ArrayList<>(documents.size());
        for (SolrInputDocument document : documents) {
            mapped.add(map(document));
        }
        return mapped;
    }

    private Object normalize(Object value) {
        if (value instanceof Date date) {
            return Instant.ofEpochMilli(date.getTime()).toString();
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().filter(item -> item != null).map(this::normalize).toList();
        }
        return value.toString();
    }

    private String semanticText(Map<String, Object> document, String fallback) {
        List<String> values = new ArrayList<>();
        for (String field : SEMANTIC_SOURCE_FIELDS) {
            appendText(values, document.get(field));
        }
        return values.isEmpty() ? fallback : String.join("\n", values);
    }

    private void appendText(List<String> values, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> appendText(values, item));
        } else if (value != null && !value.toString().isBlank()) {
            values.add(value.toString());
        }
    }
}

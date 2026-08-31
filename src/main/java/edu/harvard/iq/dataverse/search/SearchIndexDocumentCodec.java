package edu.harvard.iq.dataverse.search;

import java.io.StringReader;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

final class SearchIndexDocumentCodec {

    private SearchIndexDocumentCodec() {
    }

    static String encodeDocuments(Collection<SolrInputDocument> documents) {
        JsonArrayBuilder result = Json.createArrayBuilder();
        for (SolrInputDocument document : documents) {
            JsonObjectBuilder encodedDocument = Json.createObjectBuilder();
            for (String fieldName : document.getFieldNames()) {
                SolrInputField field = document.getField(fieldName);
                Collection<Object> values = field.getValues();
                if (values == null || values.isEmpty()) {
                    continue;
                }
                if (values.size() == 1) {
                    encodedDocument.add(fieldName, toJsonValue(values.iterator().next()));
                } else {
                    JsonArrayBuilder encodedValues = Json.createArrayBuilder();
                    values.forEach(value -> encodedValues.add(toJsonValue(value)));
                    encodedDocument.add(fieldName, encodedValues);
                }
            }
            result.add(encodedDocument);
        }
        return result.build().toString();
    }

    static Collection<SolrInputDocument> decodeDocuments(String payload) {
        JsonArray encodedDocuments = readArray(payload);
        List<SolrInputDocument> documents = new ArrayList<>(encodedDocuments.size());
        for (JsonValue value : encodedDocuments) {
            JsonObject encodedDocument = value.asJsonObject();
            SolrInputDocument document = new SolrInputDocument();
            for (Map.Entry<String, JsonValue> field : encodedDocument.entrySet()) {
                if (field.getValue().getValueType() == JsonValue.ValueType.ARRAY) {
                    for (JsonValue item : field.getValue().asJsonArray()) {
                        document.addField(field.getKey(), fromJsonValue(item));
                    }
                } else {
                    document.addField(field.getKey(), fromJsonValue(field.getValue()));
                }
            }
            documents.add(document);
        }
        return documents;
    }

    static String encodeIds(Collection<String> documentIds) {
        JsonArrayBuilder result = Json.createArrayBuilder();
        documentIds.forEach(result::add);
        return result.build().toString();
    }

    static List<String> decodeIds(String payload) {
        return readArray(payload).stream().map(value -> ((JsonString) value).getString()).toList();
    }

    private static JsonArray readArray(String payload) {
        try (var reader = Json.createReader(new StringReader(payload))) {
            return reader.readArray();
        }
    }

    private static JsonValue toJsonValue(Object value) {
        if (value == null) {
            return JsonValue.NULL;
        }
        if (value instanceof JsonValue jsonValue) {
            return jsonValue;
        }
        if (value instanceof Boolean bool) {
            return bool ? JsonValue.TRUE : JsonValue.FALSE;
        }
        if (value instanceof BigDecimal decimal) {
            return Json.createValue(decimal);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return Json.createValue(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            return Double.isFinite(doubleValue) ? Json.createValue(doubleValue) : Json.createValue(value.toString());
        }
        if (value instanceof Date date) {
            return Json.createValue(date.toInstant().toString());
        }
        if (value instanceof Instant instant) {
            return Json.createValue(instant.toString());
        }
        if (value instanceof TemporalAccessor || value instanceof Character || value instanceof Enum<?>) {
            return Json.createValue(value.toString());
        }
        if (value instanceof Collection<?> collection) {
            JsonArrayBuilder array = Json.createArrayBuilder();
            collection.forEach(item -> array.add(toJsonValue(item)));
            return array.build();
        }
        if (value.getClass().isArray()) {
            JsonArrayBuilder array = Json.createArrayBuilder();
            for (int i = 0; i < Array.getLength(value); i++) {
                array.add(toJsonValue(Array.get(value, i)));
            }
            return array.build();
        }
        return Json.createValue(value.toString());
    }

    private static Object fromJsonValue(JsonValue value) {
        return switch (value.getValueType()) {
            case STRING -> ((JsonString) value).getString();
            case NUMBER -> numberValue((JsonNumber) value);
            case TRUE -> true;
            case FALSE -> false;
            case NULL -> null;
            case ARRAY -> value.asJsonArray().stream().map(SearchIndexDocumentCodec::fromJsonValue).toList();
            case OBJECT -> value.toString();
        };
    }

    private static Number numberValue(JsonNumber value) {
        return value.isIntegral() ? value.longValueExact() : value.bigDecimalValue();
    }
}

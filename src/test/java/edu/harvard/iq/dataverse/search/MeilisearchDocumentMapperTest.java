package edu.harvard.iq.dataverse.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;

class MeilisearchDocumentMapperTest {

    private final MeilisearchDocumentMapper mapper = new MeilisearchDocumentMapper();

    @Test
    void mapsScalarMultivaluedAndDateFields() throws Exception {
        SolrInputDocument source = new SolrInputDocument();
        source.addField(SearchFields.ID, "dataset_1");
        source.addField("title", "A dataset");
        source.addField("subjects", "Biology");
        source.addField("subjects", "Medicine");
        source.addField("published", Date.from(Instant.parse("2026-01-02T03:04:05Z")));
        source.setField("empty", null);
        source.addField("_version_", 7L);

        Map<String, Object> mapped = mapper.map(source);

        assertEquals("dataset_1", mapped.get(SearchFields.ID));
        assertEquals("A dataset", mapped.get("title"));
        assertEquals(List.of("Biology", "Medicine"), mapped.get("subjects"));
        assertEquals("2026-01-02T03:04:05Z", mapped.get("published"));
        assertEquals("A dataset\nBiology\nMedicine", mapped.get(MeilisearchDocumentMapper.SEMANTIC_TEXT_FIELD));
        assertFalse(mapped.containsKey("empty"));
        assertFalse(mapped.containsKey("_version_"));
    }

    @Test
    void requiresAnId() {
        assertThrows(SearchException.class, () -> mapper.map(new SolrInputDocument()));
    }

    @Test
    void mapsEmptyInputToEmptyList() throws Exception {
        assertEquals(List.of(), mapper.mapAll(List.of()));
        assertEquals(List.of(), mapper.mapAll(null));
    }
}

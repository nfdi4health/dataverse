package edu.harvard.iq.dataverse.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;

class SearchIndexDocumentCodecTest {

    @Test
    void roundTripsSolrDocumentsThroughThePersistentPayload() {
        SolrInputDocument source = new SolrInputDocument();
        source.addField("id", "dataset_42_draft");
        source.addField("entityId", 42L);
        source.addField("datasetValid", true);
        source.addField("publicationDate", Date.from(Instant.parse("2026-08-31T09:00:00Z")));
        source.addField("subject", "Medicine");
        source.addField("subject", "Biology");

        String payload = SearchIndexDocumentCodec.encodeDocuments(List.of(source));
        Collection<SolrInputDocument> decoded = SearchIndexDocumentCodec.decodeDocuments(payload);
        SolrInputDocument document = decoded.iterator().next();

        assertTrue(payload.contains("dataset_42_draft"));
        assertEquals("dataset_42_draft", document.getFieldValue("id"));
        assertEquals(42L, document.getFieldValue("entityId"));
        assertEquals(true, document.getFieldValue("datasetValid"));
        assertEquals("2026-08-31T09:00:00Z", document.getFieldValue("publicationDate"));
        assertEquals(List.of("Medicine", "Biology"), List.copyOf(document.getFieldValues("subject")));
    }

    @Test
    void roundTripsDocumentIds() {
        String payload = SearchIndexDocumentCodec.encodeIds(List.of("dataset_1", "datafile_2_draft"));

        assertEquals(List.of("dataset_1", "datafile_2_draft"), SearchIndexDocumentCodec.decodeIds(payload));
    }
}

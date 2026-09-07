package edu.harvard.iq.dataverse.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;
import java.util.List;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DualSearchIndexAdapterTest {

    private RecordingAdapter solr;
    private RecordingAdapter meilisearch;
    private DualSearchIndexAdapter adapter;

    @BeforeEach
    void setUp() {
        solr = new RecordingAdapter("solr");
        meilisearch = new RecordingAdapter("meilisearch");
        adapter = new DualSearchIndexAdapter();
        adapter.solr = solr;
        adapter.meilisearch = meilisearch;
    }

    @Test
    void forwardsAddsAndDeletesToBothIndexes() throws Exception {
        SolrInputDocument document = new SolrInputDocument();
        document.addField(SearchFields.ID, "dataset_1");
        List<SolrInputDocument> documents = List.of(document);
        List<String> ids = List.of("dataset_1");

        adapter.add(documents);
        adapter.deleteByIds(ids);
        adapter.deleteAll();

        assertEquals(documents, solr.added);
        assertEquals(documents, meilisearch.added);
        assertEquals(ids, solr.deleted);
        assertEquals(ids, meilisearch.deleted);
        assertEquals(1, solr.deleteAllCount);
        assertEquals(1, meilisearch.deleteAllCount);
        assertEquals("dual", adapter.getServiceName());
    }

    private static class RecordingAdapter implements SearchIndexAdapter {

        private final String serviceName;
        private Collection<SolrInputDocument> added;
        private Collection<String> deleted;
        private int deleteAllCount;

        private RecordingAdapter(String serviceName) {
            this.serviceName = serviceName;
        }

        @Override
        public String getServiceName() {
            return serviceName;
        }

        @Override
        public void add(Collection<SolrInputDocument> documents) {
            added = documents;
        }

        @Override
        public void deleteByIds(Collection<String> ids) {
            deleted = ids;
        }

        @Override
        public void deleteAll() {
            deleteAllCount++;
        }
    }
}

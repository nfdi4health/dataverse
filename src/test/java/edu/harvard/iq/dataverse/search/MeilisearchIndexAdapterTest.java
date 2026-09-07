package edu.harvard.iq.dataverse.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MeilisearchIndexAdapterTest {

    private RecordingClient client;
    private MeilisearchIndexAdapter adapter;

    @BeforeEach
    void setUp() {
        client = new RecordingClient();
        adapter = new MeilisearchIndexAdapter();
        adapter.client = client;
        adapter.mapper = new MeilisearchDocumentMapper();
        adapter.configuration = new MeilisearchConfiguration();
    }

    @Test
    void addsDocumentsAndWaitsForTask() throws Exception {
        SolrInputDocument document = document("dataset_1");
        adapter.add(List.of(document));

        assertEquals(List.of(Map.of(
                SearchFields.ID, "dataset_1",
                MeilisearchDocumentMapper.SEMANTIC_TEXT_FIELD, "dataset_1")), client.addedDocuments);
        assertEquals(12L, client.requestedTaskId);
    }

    @Test
    void reportsFailedTasks() throws Exception {
        client.task = new MeilisearchClient.Task(9L, "failed", "bad document");

        SearchException exception = assertThrows(SearchException.class, adapter::deleteAll);

        assertEquals("Meilisearch task 9 failed: bad document", exception.getMessage());
    }

    @Test
    void ignoresEmptyOperations() throws Exception {
        adapter.add(List.of());
        adapter.deleteByIds(List.of());

        assertNull(client.addedDocuments);
        assertNull(client.deletedIds);
    }

    private SolrInputDocument document(String id) {
        SolrInputDocument document = new SolrInputDocument();
        document.addField(SearchFields.ID, id);
        return document;
    }

    private static class RecordingClient implements MeilisearchClient {

        private List<Map<String, Object>> addedDocuments;
        private Collection<String> deletedIds;
        private long requestedTaskId;
        private Task task = new Task(12L, "succeeded", null);

        @Override
        public long addDocuments(List<Map<String, Object>> documents) {
            addedDocuments = documents;
            return task.uid();
        }

        @Override
        public long deleteDocuments(Collection<String> ids) {
            deletedIds = ids;
            return task.uid();
        }

        @Override
        public long deleteAllDocuments() {
            return task.uid();
        }

        @Override
        public Task getTask(long taskId) {
            requestedTaskId = taskId;
            return task;
        }
    }
}

package edu.harvard.iq.dataverse.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SolrIndexAdapterTest {

    private SolrClient solrClient;
    private SolrIndexAdapter adapter;

    @BeforeEach
    void setUp() {
        solrClient = mock(SolrClient.class);
        SolrClientIndexService clientService = new SolrClientIndexService();
        clientService.setSolrClient(solrClient);

        adapter = new SolrIndexAdapter();
        adapter.clientService = clientService;
    }

    @Test
    void exposesSolrServiceName() {
        assertEquals(SearchServiceFactory.INTERNAL_SOLR_SERVICE_NAME, adapter.getServiceName());
    }

    @Test
    void addsDocuments() throws Exception {
        SolrInputDocument document = new SolrInputDocument();
        document.addField(SearchFields.ID, "dataset_1");

        adapter.add(List.of(document));

        verify(solrClient).add(List.of(document));
    }

    @Test
    void ignoresEmptyAddAndDeleteRequests() throws Exception {
        adapter.add(List.of());
        adapter.add(null);
        adapter.deleteByIds(List.of());
        adapter.deleteByIds(null);

        verify(solrClient, never()).add(List.of());
        verify(solrClient, never()).deleteById(List.of());
    }

    @Test
    void deletesDocumentsById() throws Exception {
        List<String> ids = List.of("dataset_1", "datafile_2");

        adapter.deleteByIds(ids);

        verify(solrClient).deleteById(ids);
    }

    @Test
    void deletesAllDocuments() throws Exception {
        adapter.deleteAll();

        verify(solrClient).deleteByQuery("*:*");
    }

    @Test
    void flushesPendingOperations() throws Exception {
        adapter.flush();

        verify(solrClient).commit();
    }

    @Test
    void wrapsClientFailures() throws Exception {
        IOException cause = new IOException("unavailable");
        when(solrClient.deleteById(List.of("dataset_1"))).thenThrow(cause);

        SearchException exception = assertThrows(SearchException.class,
                () -> adapter.deleteByIds(List.of("dataset_1")));

        assertSame(cause, exception.getCause());
        assertEquals("Unable to delete documents in solr index", exception.getMessage());
    }
}

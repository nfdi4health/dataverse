package edu.harvard.iq.dataverse.search;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import edu.harvard.iq.dataverse.search.SearchIndexOperation.Backend;

class SearchIndexDispatcherTest {

    private SearchIndexOperationServiceBean operationService;
    private SolrSearchIndexBackend solrBackend;
    private MeilisearchSearchIndexBackend meilisearchBackend;
    private SearchIndexDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        operationService = mock(SearchIndexOperationServiceBean.class);
        solrBackend = mock(SolrSearchIndexBackend.class);
        meilisearchBackend = mock(MeilisearchSearchIndexBackend.class);
        dispatcher = new SearchIndexDispatcher();
        dispatcher.operationService = operationService;
        dispatcher.solrBackend = solrBackend;
        dispatcher.meilisearchBackend = meilisearchBackend;
    }

    @Test
    void removesAnOperationOnlyAfterBackendSuccess() throws Exception {
        SearchIndexOperation operation = mock(SearchIndexOperation.class);
        when(operation.getId()).thenReturn(17L);
        when(operationService.claimNext(Backend.SOLR))
                .thenReturn(Optional.of(operation), Optional.empty());
        when(operationService.claimNext(Backend.MEILISEARCH)).thenReturn(Optional.empty());

        dispatcher.dispatch();

        verify(solrBackend).execute(operation);
        verify(operationService).complete(17L);
        verify(operationService, never()).recordFailure(17L, null);
    }

    @Test
    void recordsFailureAndDoesNotProcessLaterOperations() throws Exception {
        SearchIndexOperation operation = mock(SearchIndexOperation.class);
        when(operation.getId()).thenReturn(23L);
        when(operation.getAttemptCount()).thenReturn(3);
        when(operationService.claimNext(Backend.SOLR)).thenReturn(Optional.of(operation));
        when(operationService.claimNext(Backend.MEILISEARCH)).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new IllegalStateException("down")).when(solrBackend).execute(operation);

        dispatcher.dispatch();

        verify(operationService).recordFailure(23L, "IllegalStateException: down");
        verify(operationService, never()).complete(23L);
        verify(operationService).claimNext(Backend.SOLR);
    }
}

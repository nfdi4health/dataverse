package edu.harvard.iq.dataverse.search;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import edu.harvard.iq.dataverse.search.SearchIndexOperation.OperationType;

@ExtendWith(MockitoExtension.class)
class SearchIndexCoordinatorTest {

    @Mock
    private SearchIndexOperationServiceBean operationService;

    private SearchIndexCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new SearchIndexCoordinator();
        coordinator.operationService = operationService;
    }

    @Test
    void fansContentUpsertsOutThroughTheContentQueue() {
        SolrInputDocument document = new SolrInputDocument();
        document.addField("id", "dataset_1");

        coordinator.upsertContent(List.of(document));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(operationService).enqueueContent(eq(OperationType.UPSERT), payload.capture());
        assertTrue(payload.getValue().contains("dataset_1"));
    }

    @Test
    void routesPermissionDeletesOnlyToSolr() {
        coordinator.delete(List.of("dataset_1", "dataset_1_permission"));

        verify(operationService).enqueueContent(OperationType.DELETE, "[\"dataset_1\"]");
        verify(operationService).enqueueSolr(OperationType.DELETE, "[\"dataset_1_permission\"]");
    }
}

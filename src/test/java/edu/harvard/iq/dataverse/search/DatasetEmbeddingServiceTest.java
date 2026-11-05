package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@LocalJvmSettings
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DatasetEmbeddingServiceTest {

    @Test
    void emptySourceSkipsBothSchemaAndEmbeddingEndpoints() throws Exception {
        try (EmbeddingConfigurationTest.Settings settings = new EmbeddingConfigurationTest.Settings()) {
            DatasetEmbeddingService service = new DatasetEmbeddingService();
            service.processor = new BasicDocumentEmbeddingProcessor();
            service.client = mock(EmbeddingClient.class);
            service.schemaValidator = mock(EmbeddingSchemaValidator.class);
            SolrInputDocument document = new SolrInputDocument();
            document.setField(SearchFields.ID, "dataset_42");
            service.addEmbedding(document, "http://solr.invalid/solr/collection1");
            verifyNoInteractions(service.client, service.schemaValidator);
            assertEquals(1, document.size());
        }
    }

    @Test
    void pipelineFailureDoesNotPartiallyAttachVectorOrProvenance() throws Exception {
        try (EmbeddingConfigurationTest.Settings settings = new EmbeddingConfigurationTest.Settings()) {
            DatasetEmbeddingService service = new DatasetEmbeddingService();
            service.processor = mock(DocumentEmbeddingProcessor.class);
            service.client = mock(EmbeddingClient.class);
            service.schemaValidator = mock(EmbeddingSchemaValidator.class);
            when(service.processor.prepare(any(), any())).thenReturn("document");
            when(service.client.embed(any(), any())).thenReturn(new EmbeddingClient.Result(java.util.List.of(1f), "model"));
            when(service.processor.pipelineIdentifier(any())).thenThrow(new IllegalStateException("processor failure"));
            SolrInputDocument document = new SolrInputDocument();
            document.setField("title", "document");
            assertThrows(IllegalStateException.class,
                    () -> service.addEmbedding(document, "http://solr.invalid/solr/collection1"));
            assertEquals(1, document.size());
            assertFalse(document.containsKey(SearchFields.DATASET_EMBEDDING));
            assertFalse(document.containsKey(SearchFields.EMBEDDER));
            assertFalse(document.containsKey(SearchFields.EMBEDDING_SOURCE_HASH));
            assertFalse(document.containsKey(SearchFields.EMBEDDING_PIPELINE));
        }
    }
}

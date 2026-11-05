package edu.harvard.iq.dataverse.search;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.apache.solr.common.SolrInputDocument;

/** Adds optional embedding fields to an otherwise complete, version-specific dataset document. */
@Dependent
public class DatasetEmbeddingService {

    @Inject
    EmbeddingClient client;
    @Inject
    EmbeddingSchemaValidator schemaValidator;
    @Inject
    DocumentEmbeddingProcessor processor;

    public void addEmbedding(SolrInputDocument document, String solrUrl) throws EmbeddingException {
        EmbeddingConfiguration configuration = EmbeddingConfiguration.load();
        String text = processor.prepare(document, configuration);
        if (text.isEmpty()) {
            return;
        }
        schemaValidator.validate(solrUrl, configuration);
        EmbeddingClient.Result result = client.embed(text, configuration);
        String hash = BasicDocumentEmbeddingProcessor.sha256(text);
        String pipeline = processor.pipelineIdentifier(configuration);
        document.setField(SearchFields.DATASET_EMBEDDING, result.vector());
        document.setField(SearchFields.EMBEDDER, result.modelIdentity());
        document.setField(SearchFields.EMBEDDING_SOURCE_HASH, hash);
        document.setField(SearchFields.EMBEDDING_PIPELINE, pipeline);
    }

    static void removeEmbedding(SolrInputDocument document) {
        document.removeField(SearchFields.DATASET_EMBEDDING);
        document.removeField(SearchFields.EMBEDDER);
        document.removeField(SearchFields.EMBEDDING_SOURCE_HASH);
        document.removeField(SearchFields.EMBEDDING_PIPELINE);
    }
}

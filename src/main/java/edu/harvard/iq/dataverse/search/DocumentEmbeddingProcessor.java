package edu.harvard.iq.dataverse.search;

import org.apache.solr.common.SolrInputDocument;

/** Prepares the exact embedding input from the dataset document for the version being indexed. */
public interface DocumentEmbeddingProcessor {

    String prepare(SolrInputDocument document, EmbeddingConfiguration configuration) throws EmbeddingException;

    /** Identifies both the implemented text contract and its encoding configuration. */
    String pipelineIdentifier(EmbeddingConfiguration configuration);
}

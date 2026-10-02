package edu.harvard.iq.dataverse.search;

import java.util.Collection;

import jakarta.ejb.Singleton;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.solr.common.SolrInputDocument;

@Singleton
@Named("dualSearchIndexAdapter")
public class DualSearchIndexAdapter implements SearchIndexAdapter {

    @Inject
    @Named("solrIndexAdapter")
    SearchIndexAdapter solr;

    @Inject
    @Named("meilisearchIndexAdapter")
    SearchIndexAdapter meilisearch;

    @Override
    public String getServiceName() {
        return "dual";
    }

    @Override
    public void add(Collection<SolrInputDocument> documents) throws SearchException {
        solr.add(documents);
        meilisearch.add(documents);
    }

    @Override
    public void deleteByIds(Collection<String> ids) throws SearchException {
        solr.deleteByIds(ids);
        meilisearch.deleteByIds(ids);
    }

    @Override
    public void deleteAll() throws SearchException {
        solr.deleteAll();
        meilisearch.deleteAll();
    }
}

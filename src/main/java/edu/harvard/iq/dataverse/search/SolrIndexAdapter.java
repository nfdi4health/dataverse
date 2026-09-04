package edu.harvard.iq.dataverse.search;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import jakarta.ejb.Singleton;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;

@Singleton
@Named("solrIndexAdapter")
public class SolrIndexAdapter implements SearchIndexAdapter {
    @Inject
    SolrClientIndexService clientService;

    @Override
    public void add(Collection<SolrInputDocument> documents) throws SearchException {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            clientService.getSolrClient().add(documents);
        } catch (SolrServerException | IOException ex) {
            throw failure("add documents", ex);
        }
    }

    @Override
    public void deleteByIds(Collection<String> ids) throws SearchException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            clientService.getSolrClient().deleteById(List.copyOf(ids));
        } catch (SolrServerException | IOException ex) {
            throw failure("delete documents", ex);
        }
    }

    @Override
    public void deleteAll() throws SearchException {
        try {
            clientService.getSolrClient().deleteByQuery("*:*");
        } catch (SolrServerException | IOException ex) {
            throw failure("delete all documents", ex);
        }
    }

    @Override
    public void flush() throws SearchException {
        try {
            clientService.getSolrClient().commit();
        } catch (SolrServerException | IOException ex) {
            throw failure("flush pending operations", ex);
        }
    }

    @Override
    public String getServiceName() {
        return SearchServiceFactory.INTERNAL_SOLR_SERVICE_NAME;
    }

    private SearchException failure(String operation, Exception cause) {
        return new SearchException("Unable to " + operation + " in " + getServiceName() + " index", cause);
    }
}

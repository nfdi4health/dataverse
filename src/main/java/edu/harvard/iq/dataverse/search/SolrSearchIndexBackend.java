package edu.harvard.iq.dataverse.search;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class SolrSearchIndexBackend implements SearchIndexBackend {

    @EJB
    SolrClientService solrClientService;

    @Override
    public SearchIndexOperation.Backend getBackend() {
        return SearchIndexOperation.Backend.SOLR;
    }

    @Override
    public void execute(SearchIndexOperation operation) throws Exception {
        switch (operation.getOperationType()) {
            case UPSERT -> solrClientService.getSolrClient()
                    .add(SearchIndexDocumentCodec.decodeDocuments(operation.getPayload()));
            case DELETE -> solrClientService.getSolrClient()
                    .deleteById(SearchIndexDocumentCodec.decodeIds(operation.getPayload()));
            case DELETE_ALL -> solrClientService.getSolrClient().deleteByQuery("*:*");
        }
    }
}

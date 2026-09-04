package edu.harvard.iq.dataverse.search;

import java.util.Collection;

import org.apache.solr.common.SolrInputDocument;

public interface SearchIndexAdapter {

    String getServiceName();

    void add(Collection<SolrInputDocument> documents) throws SearchException;

    void deleteByIds(Collection<String> ids) throws SearchException;

    void deleteAll() throws SearchException;

    /**
     * Make previously submitted operations observable to subsequent searches.
     */
    void flush() throws SearchException;
}

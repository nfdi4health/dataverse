package edu.harvard.iq.dataverse.search;

interface SearchIndexBackend {

    SearchIndexOperation.Backend getBackend();

    void execute(SearchIndexOperation operation) throws Exception;
}

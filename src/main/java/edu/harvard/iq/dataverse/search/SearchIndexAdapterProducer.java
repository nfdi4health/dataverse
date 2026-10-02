package edu.harvard.iq.dataverse.search;

import java.util.logging.Level;
import java.util.logging.Logger;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;

public class SearchIndexAdapterProducer {

    private static final Logger logger = Logger.getLogger(SearchIndexAdapterProducer.class.getCanonicalName());

    @Inject
    @Named("solrIndexAdapter")
    SearchIndexAdapter solr;

    @Inject
    @Named("meilisearchIndexAdapter")
    SearchIndexAdapter meilisearch;

    @Inject
    @Named("dualSearchIndexAdapter")
    SearchIndexAdapter dual;

    @Produces
    @Named("configuredIndexAdapter")
    public SearchIndexAdapter configuredAdapter() {
        String service = JvmSettings.INDEX_SERVICE.lookupOptional().orElse("dual");
        logger.log(Level.INFO, "Using {0} as the search index service", service);
        return switch (service) {
            case "solr" -> solr;
            case "meilisearch" -> meilisearch;
            case "dual" -> dual;
            default -> throw new IllegalStateException("Unsupported search index service: " + service);
        };
    }
}

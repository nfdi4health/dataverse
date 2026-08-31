package edu.harvard.iq.dataverse.search;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.harvard.iq.dataverse.search.SearchIndexOperation.Backend;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.DependsOn;
import jakarta.ejb.EJB;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;

@Singleton
@Startup
@DependsOn("StartupFlywayMigrator")
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class SearchIndexDispatcher {

    private static final Logger logger = Logger.getLogger(SearchIndexDispatcher.class.getCanonicalName());
    private static final int MAX_OPERATIONS_PER_RUN = 100;

    @Resource
    ManagedScheduledExecutorService scheduler;

    @EJB
    SearchIndexOperationServiceBean operationService;

    @EJB
    SolrSearchIndexBackend solrBackend;

    @EJB
    MeilisearchSearchIndexBackend meilisearchBackend;

    @PostConstruct
    void init() {
        scheduler.scheduleWithFixedDelay(() -> dispatch(Backend.SOLR, solrBackend), 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(() -> dispatch(Backend.MEILISEARCH, meilisearchBackend), 1, 1,
                TimeUnit.SECONDS);
    }

    public void dispatch() {
        dispatch(Backend.SOLR, solrBackend);
        dispatch(Backend.MEILISEARCH, meilisearchBackend);
    }

    private void dispatch(Backend backend, SearchIndexBackend indexBackend) {
        for (int i = 0; i < MAX_OPERATIONS_PER_RUN; i++) {
            Optional<SearchIndexOperation> claimed = operationService.claimNext(backend);
            if (claimed.isEmpty()) {
                return;
            }

            SearchIndexOperation operation = claimed.get();
            try {
                indexBackend.execute(operation);
                operationService.complete(operation.getId());
            } catch (Exception exception) {
                String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                operationService.recordFailure(operation.getId(), error);
                logger.log(Level.WARNING,
                        "Search index operation {0} failed for {1} on attempt {2}: {3}",
                        new Object[]{operation.getId(), backend, operation.getAttemptCount(), error});
                return;
            }
        }
    }
}

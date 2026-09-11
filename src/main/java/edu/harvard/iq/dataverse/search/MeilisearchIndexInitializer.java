package edu.harvard.iq.dataverse.search;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;

/** Creates and configures the Meilisearch index when the integration is enabled. */
@Startup
@Singleton
public class MeilisearchIndexInitializer {

    private static final Logger logger = Logger.getLogger(MeilisearchIndexInitializer.class.getCanonicalName());

    @Inject
    MeilisearchClient client;

    @Inject
    MeilisearchConfiguration configuration;

    @PostConstruct
    void initializeAtStartup() {
        if (!isEnabled()) {
            logger.fine("Meilisearch is not configured; index initialization is disabled");
            return;
        }
        try {
            initialize();
        } catch (SearchException | IllegalArgumentException ex) {
            if (isRequired()) {
                throw new IllegalStateException("Unable to initialize required Meilisearch index", ex);
            }
            logger.log(Level.WARNING,
                    "Unable to initialize optional Meilisearch index; Solr remains available: " + ex.getMessage(),
                    ex);
        }
    }

    void initialize() throws SearchException {
        if (!client.indexExists()) {
            logger.log(Level.INFO, "Creating Meilisearch index {0}", configuration.index());
            await(client.createIndex(SearchFields.ID));
        }

        Map<String, Object> desired = configuration.indexSettings();
        Map<String, Object> current = client.getSettings();
        if (!containsSettings(current, desired)) {
            logger.log(Level.INFO, "Applying Dataverse settings to Meilisearch index {0}", configuration.index());
            await(client.updateSettings(desired));
        } else {
            logger.log(Level.FINE, "Meilisearch index {0} already has the required settings",
                    configuration.index());
        }
    }

    static boolean containsSettings(Map<String, Object> current, Map<String, Object> desired) {
        for (Map.Entry<String, Object> entry : desired.entrySet()) {
            if (!settingsEqual(entry.getValue(), current.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean settingsEqual(Object desired, Object current) {
        if (desired instanceof Number desiredNumber && current instanceof Number currentNumber) {
            return new BigDecimal(desiredNumber.toString()).compareTo(new BigDecimal(currentNumber.toString())) == 0;
        }
        if (desired instanceof Map<?, ?> desiredMap && current instanceof Map<?, ?> currentMap) {
            for (Map.Entry<?, ?> entry : desiredMap.entrySet()) {
                if (!settingsEqual(entry.getValue(), currentMap.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (desired instanceof List<?> desiredList && current instanceof List<?> currentList) {
            if (desiredList.size() != currentList.size()) {
                return false;
            }
            for (int index = 0; index < desiredList.size(); index++) {
                if (!settingsEqual(desiredList.get(index), currentList.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return desired == null ? current == null : desired.equals(current);
    }

    private void await(long taskId) throws SearchException {
        Instant deadline = Instant.now().plus(configuration.taskTimeout());
        while (Instant.now().isBefore(deadline)) {
            MeilisearchClient.Task task = client.getTask(taskId);
            if (task.finished()) {
                if (task.succeeded()) {
                    return;
                }
                throw new SearchException("Meilisearch task " + taskId + " " + task.status()
                        + (task.error() == null ? "" : ": " + task.error()), null);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SearchException("Interrupted while waiting for Meilisearch task " + taskId, ex);
            }
        }
        throw new SearchException("Timed out waiting for Meilisearch task " + taskId, null);
    }

    private boolean isEnabled() {
        if (JvmSettings.MEILISEARCH_URL.lookupOptional().isEmpty()) {
            return false;
        }
        String indexService = JvmSettings.INDEX_SERVICE.lookupOptional().orElse("solr");
        String searchService = JvmSettings.DEFAULT_SEARCH_SERVICE.lookupOptional()
                .orElse(SearchServiceFactory.INTERNAL_SOLR_SERVICE_NAME);
        return "dual".equals(indexService) || "meilisearch".equals(indexService)
                || MeilisearchSearchServiceBean.SERVICE_NAME.equals(searchService);
    }

    private boolean isRequired() {
        String indexService = JvmSettings.INDEX_SERVICE.lookupOptional().orElse("solr");
        String searchService = JvmSettings.DEFAULT_SEARCH_SERVICE.lookupOptional()
                .orElse(SearchServiceFactory.INTERNAL_SOLR_SERVICE_NAME);
        return "meilisearch".equals(indexService)
                || MeilisearchSearchServiceBean.SERVICE_NAME.equals(searchService);
    }
}

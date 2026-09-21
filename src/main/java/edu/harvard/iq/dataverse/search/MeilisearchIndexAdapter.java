package edu.harvard.iq.dataverse.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.ejb.Singleton;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.solr.common.SolrInputDocument;

@Singleton
@Named("meilisearchIndexAdapter")
public class MeilisearchIndexAdapter implements SearchIndexAdapter {

    private static final Logger logger = Logger.getLogger(MeilisearchIndexAdapter.class.getCanonicalName());

    @Inject
    MeilisearchClient client;

    @Inject
    MeilisearchDocumentMapper mapper;

    @Inject
    MeilisearchConfiguration configuration;

    @Override
    public String getServiceName() {
        return "meilisearch";
    }

    @Override
    public void add(Collection<SolrInputDocument> documents) throws SearchException {
        List<SolrInputDocument> docsToIndex = documents
            .stream()
            .filter(doc -> {
                Object id = doc.getFieldValue("id");

                boolean isNonPermissionDoc = (id != null) &&
                    !(id.toString()
                        .endsWith(IndexServiceBean.discoverabilityPermissionSuffix));

                return isNonPermissionDoc;
            })
            .toList();

        List<java.util.Map<String, Object>> mapped = mapper.mapAll(docsToIndex);
        if (mapped.isEmpty()) {
            return;
        }
        int batchSize = configuration.batchSize();
        if (batchSize < 1) {
            throw new SearchException("Meilisearch batch size must be greater than zero", null);
        }
        logger.log(Level.INFO, "Submitting {0} documents to Meilisearch in batches of {1}",
                new Object[] { mapped.size(), batchSize });
        for (int start = 0; start < mapped.size(); start += batchSize) {
            int end = Math.min(start + batchSize, mapped.size());
            long taskId = client.addDocuments(new ArrayList<>(mapped.subList(start, end)));
            logger.log(Level.FINE, "Submitted Meilisearch document batch as task {0}", taskId);
            waitForTask(taskId, Instant.now().plus(configuration.taskTimeout()));
        }
    }

    @Override
    public void deleteByIds(Collection<String> ids) throws SearchException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        long taskId = client.deleteDocuments(List.copyOf(ids));
        logger.log(Level.INFO, "Submitted deletion of {0} Meilisearch documents as task {1}",
                new Object[] { ids.size(), taskId });
        waitForTask(taskId, Instant.now().plus(configuration.taskTimeout()));
    }

    @Override
    public void deleteAll() throws SearchException {
        long taskId = client.deleteAllDocuments();
        logger.log(Level.INFO, "Submitted deletion of all Meilisearch documents as task {0}", taskId);
        waitForTask(taskId, Instant.now().plus(configuration.taskTimeout()));
    }

    private void waitForTask(long taskId, Instant deadline) throws SearchException {
        while (Instant.now().isBefore(deadline)) {
            MeilisearchClient.Task task = client.getTask(taskId);
            if (task.finished()) {
                if (!task.succeeded()) {
                    logger.log(Level.SEVERE, "Meilisearch task {0} ended with status {1}: {2}",
                            new Object[] { taskId, task.status(), task.error() });
                    throw new SearchException("Meilisearch task " + taskId + " " + task.status()
                            + (task.error() == null ? "" : ": " + task.error()), null);
                }
                logger.log(Level.FINE, "Meilisearch task {0} succeeded", taskId);
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SearchException("Interrupted while waiting for Meilisearch task " + taskId, ex);
            }
        }
        logger.log(Level.SEVERE, "Timed out waiting for Meilisearch task {0}", taskId);
        throw new SearchException("Timed out waiting for Meilisearch task " + taskId, null);
    }
}

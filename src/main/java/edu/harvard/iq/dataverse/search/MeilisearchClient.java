package edu.harvard.iq.dataverse.search;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface MeilisearchClient {

    long addDocuments(List<Map<String, Object>> documents) throws SearchException;

    long deleteDocuments(Collection<String> ids) throws SearchException;

    long deleteAllDocuments() throws SearchException;

    Task getTask(long taskId) throws SearchException;

    record Task(long uid, String status, String error) {
        public boolean finished() {
            return "succeeded".equals(status) || "failed".equals(status) || "canceled".equals(status);
        }

        public boolean succeeded() {
            return "succeeded".equals(status);
        }
    }
}

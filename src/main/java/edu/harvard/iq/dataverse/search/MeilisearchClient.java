package edu.harvard.iq.dataverse.search;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface MeilisearchClient {

    boolean indexExists() throws SearchException;

    long createIndex(String primaryKey) throws SearchException;

    Map<String, Object> getSettings() throws SearchException;

    long updateSettings(Map<String, Object> settings) throws SearchException;

    SearchResult search(SearchRequest request) throws SearchException;

    long addDocuments(List<Map<String, Object>> documents) throws SearchException;

    long deleteDocuments(Collection<String> ids) throws SearchException;

    long deleteAllDocuments() throws SearchException;

    Task getTask(long taskId) throws SearchException;

    final class SearchRequest {
        private final String query;
        private final int limit;
        private final String filter;
        private final String embedder;
        private final Double semanticRatio;

        public SearchRequest(String query, int limit, String filter, String embedder, Double semanticRatio) {
            this.query = query;
            this.limit = limit;
            this.filter = filter;
            this.embedder = embedder;
            this.semanticRatio = semanticRatio;
        }

        public String query() { return query; }
        public int limit() { return limit; }
        public String filter() { return filter; }
        public String embedder() { return embedder; }
        public Double semanticRatio() { return semanticRatio; }
    }

    final class SearchHit {
        private final String persistentId;
        private final float rankingScore;

        public SearchHit(String persistentId, float rankingScore) {
            this.persistentId = persistentId;
            this.rankingScore = rankingScore;
        }

        public String persistentId() { return persistentId; }
        public float rankingScore() { return rankingScore; }
    }

    final class SearchResult {
        private final List<SearchHit> hits;

        public SearchResult(List<SearchHit> hits) {
            this.hits = hits;
        }

        public List<SearchHit> hits() { return hits; }
    }

    final class Task {
        private final long uid;
        private final String status;
        private final String error;

        public Task(long uid, String status, String error) {
            this.uid = uid;
            this.status = status;
            this.error = error;
        }

        public long uid() { return uid; }
        public String status() { return status; }
        public String error() { return error; }

        public boolean finished() {
            return "succeeded".equals(status) || "failed".equals(status) || "canceled".equals(status);
        }

        public boolean succeeded() {
            return "succeeded".equals(status);
        }
    }
}

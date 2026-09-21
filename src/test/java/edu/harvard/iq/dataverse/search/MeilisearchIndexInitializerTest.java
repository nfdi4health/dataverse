package edu.harvard.iq.dataverse.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MeilisearchIndexInitializerTest {

    @Test
    void createsAndConfiguresMissingIndex() throws Exception {
        RecordingClient client = new RecordingClient(false, Map.of());
        MeilisearchIndexInitializer initializer = initializer(client);

        initializer.initialize();

        assertTrue(client.created);
        assertEquals(SearchFields.ID, client.primaryKey);
        assertEquals(requiredSettings(), client.updatedSettings);
        assertEquals(List.of(1L, 2L), client.awaitedTasks);
    }

    @Test
    void doesNotReapplyMatchingSettings() throws Exception {
        RecordingClient client = new RecordingClient(true, requiredSettings());

        initializer(client).initialize();

        assertFalse(client.created);
        assertTrue(client.updatedSettings.isEmpty());
        assertTrue(client.awaitedTasks.isEmpty());
    }

    @Test
    void comparesOnlySettingsOwnedByDataverse() {
        Map<String, Object> current = new java.util.HashMap<>(requiredSettings());
        current.put("typoTolerance", Map.of("enabled", true));

        assertTrue(MeilisearchIndexInitializer.containsSettings(current, requiredSettings()));
    }

    @Test
    void treatsEquivalentJsonNumberTypesAsEqual() {
        Map<String, Object> current = new java.util.HashMap<>(requiredSettings());
        current.put("pagination", Map.of("maxTotalHits", new java.math.BigDecimal("1000")));
        current.put("searchCutoffMs", new java.math.BigDecimal("30000"));

        assertTrue(MeilisearchIndexInitializer.containsSettings(current, requiredSettings()));
    }

    private static MeilisearchIndexInitializer initializer(RecordingClient client) {
        MeilisearchIndexInitializer initializer = new MeilisearchIndexInitializer();
        initializer.client = client;
        initializer.configuration = new TestConfiguration();
        return initializer;
    }

    private static Map<String, Object> requiredSettings() {
        return new TestConfiguration().indexSettings();
    }

    private static class TestConfiguration extends MeilisearchConfiguration {
        @Override public int candidateLimit() { return 1000; }
        @Override public Duration requestTimeout() { return Duration.ofSeconds(30); }
        @Override public Duration taskTimeout() { return Duration.ofSeconds(1); }
    }

    private static class RecordingClient implements MeilisearchClient {
        private final boolean exists;
        private final Map<String, Object> settings;
        private boolean created;
        private String primaryKey;
        private Map<String, Object> updatedSettings = Map.of();
        private final List<Long> awaitedTasks = new java.util.ArrayList<>();

        private RecordingClient(boolean exists, Map<String, Object> settings) {
            this.exists = exists;
            this.settings = settings;
        }

        @Override public boolean indexExists() { return exists; }
        @Override public long createIndex(String primaryKey) {
            created = true;
            this.primaryKey = primaryKey;
            return 1;
        }
        @Override public Map<String, Object> getSettings() { return settings; }
        @Override public long updateSettings(Map<String, Object> settings) {
            updatedSettings = settings;
            return 2;
        }
        @Override public Task getTask(long taskId) {
            awaitedTasks.add(taskId);
            return new Task(taskId, "succeeded", null);
        }
        @Override public SearchResult search(SearchRequest request) { return new SearchResult(List.of()); }
        @Override public long addDocuments(List<Map<String, Object>> documents) { return 0; }
        @Override public long deleteDocuments(Collection<String> ids) { return 0; }
        @Override public long deleteAllDocuments() { return 0; }
    }
}

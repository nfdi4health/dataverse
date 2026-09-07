package edu.harvard.iq.dataverse.search;

import java.time.Duration;
import java.util.Optional;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MeilisearchConfiguration {

    static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;
    static final int DEFAULT_TASK_TIMEOUT_SECONDS = 300;

    public String url() {
        return JvmSettings.MEILISEARCH_URL.lookupOptional().orElse("http://localhost:7700");
    }

    public Optional<String> apiKey() {
        return JvmSettings.MEILISEARCH_API_KEY.lookupOptional()
                .filter(key -> !key.isBlank());
    }

    public String index() {
        return JvmSettings.MEILISEARCH_INDEX.lookupOptional().orElse("dataverse");
    }

    public int batchSize() {
        return JvmSettings.MEILISEARCH_BATCH_SIZE.lookupOptional(Integer.class).orElse(500);
    }

    public Optional<String> embedder() {
        return JvmSettings.MEILISEARCH_EMBEDDER.lookupOptional()
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    public Optional<Double> semanticRatio() {
        Optional<Double> ratio = JvmSettings.MEILISEARCH_SEMANTIC_RATIO.lookupOptional(Double.class);
        ratio.ifPresent(value -> {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("Meilisearch semantic ratio must be between 0 and 1");
            }
        });
        return ratio;
    }

    public Duration connectTimeout() {
        return positiveDuration(JvmSettings.MEILISEARCH_CONNECT_TIMEOUT_SECONDS,
                DEFAULT_CONNECT_TIMEOUT_SECONDS);
    }

    public Duration requestTimeout() {
        return positiveDuration(JvmSettings.MEILISEARCH_REQUEST_TIMEOUT_SECONDS,
                DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    public Duration taskTimeout() {
        return positiveDuration(JvmSettings.MEILISEARCH_TASK_TIMEOUT_SECONDS,
                DEFAULT_TASK_TIMEOUT_SECONDS);
    }

    private Duration positiveDuration(JvmSettings setting, int defaultSeconds) {
        int seconds = setting.lookupOptional(Integer.class).orElse(defaultSeconds);
        if (seconds < 1) {
            throw new IllegalArgumentException(setting.getScopedKey() + " must be greater than zero");
        }
        return Duration.ofSeconds(seconds);
    }
}

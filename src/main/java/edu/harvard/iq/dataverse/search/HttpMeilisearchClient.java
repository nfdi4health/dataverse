package edu.harvard.iq.dataverse.search;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.ejb.Singleton;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

@Singleton
public class HttpMeilisearchClient implements MeilisearchClient {

    private static final Logger logger = Logger.getLogger(HttpMeilisearchClient.class.getCanonicalName());

    private HttpClient httpClient;

    @Inject
    MeilisearchConfiguration configuration;

    public HttpMeilisearchClient() {
    }

    HttpMeilisearchClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder().connectTimeout(configuration.connectTimeout()).build();
        }
        return httpClient;
    }

    @Override
    public boolean indexExists() throws SearchException {
        return send("GET", indexPath(""), null, 404).containsKey("uid");
    }

    @Override
    public long createIndex(String primaryKey) throws SearchException {
        return taskUid(send("POST", "/indexes", Map.of(
                "uid", configuration.index(),
                "primaryKey", primaryKey)));
    }

    @Override
    public Map<String, Object> getSettings() throws SearchException {
        return send("GET", indexPath("/settings"), null);
    }

    @Override
    public long updateSettings(Map<String, Object> settings) throws SearchException {
        return taskUid(send("PATCH", indexPath("/settings"), settings));
    }

    @Override
    public SearchResult search(SearchRequest searchRequest) throws SearchException {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("q", searchRequest.query());
        body.put("offset", 0);
        body.put("limit", searchRequest.limit());
        body.put("filter", searchRequest.filter());
        body.put("attributesToRetrieve", List.of(SearchFields.DATASET_PERSISTENT_ID));
        body.put("showRankingScore", true);
        if (searchRequest.embedder() != null && !searchRequest.query().isBlank()) {
            body.put("hybrid", Map.of(
                    "embedder", searchRequest.embedder(),
                    "semanticRatio", searchRequest.semanticRatio()));
        }

        Map<String, Object> response = send("POST", indexPath("/search"), body);
        Object rawHits = response.get("hits");
        if (!(rawHits instanceof List<?> hits)) {
            throw new SearchException("Meilisearch response does not contain a hits array", null);
        }
        List<SearchHit> parsed = new java.util.ArrayList<>(hits.size());
        for (Object rawHit : hits) {
            if (!(rawHit instanceof Map<?, ?> hit)) {
                throw new SearchException("Meilisearch returned a non-object hit", null);
            }
            Object rawPid = hit.get(SearchFields.DATASET_PERSISTENT_ID);
            if (rawPid == null || rawPid.toString().isBlank()) {
                throw new SearchException("Meilisearch hit does not contain a non-empty "
                        + SearchFields.DATASET_PERSISTENT_ID, null);
            }
            Object rawScore = hit.get("_rankingScore");
            float score = rawScore instanceof Number number ? number.floatValue() : 0F;
            parsed.add(new SearchHit(rawPid.toString(), score));
        }
        return new SearchResult(List.copyOf(parsed));
    }

    @Override
    public long addDocuments(List<Map<String, Object>> documents) throws SearchException {
        return taskUid(send("POST", indexPath("/documents"), documents));
    }

    @Override
    public long deleteDocuments(Collection<String> ids) throws SearchException {
        return taskUid(send("POST", indexPath("/documents/delete-batch"), ids));
    }

    @Override
    public long deleteAllDocuments() throws SearchException {
        return taskUid(send("DELETE", indexPath("/documents"), null));
    }

    @Override
    public Task getTask(long taskId) throws SearchException {
        Map<String, Object> response = send("GET", "/tasks/" + taskId, null);
        String error = null;
        if (response.get("error") instanceof Map<?, ?> details) {
            Object message = details.get("message");
            error = message == null ? details.toString() : message.toString();
        }
        return new Task(taskId, String.valueOf(response.get("status")), error);
    }

    private String indexPath(String suffix) throws SearchException {
        String index = configuration.index();
        if (!index.matches("[A-Za-z0-9_-]+")) {
            throw new SearchException("Invalid Meilisearch index name", null);
        }
        return "/indexes/" + index + suffix;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String method, String path, Object body) throws SearchException {
        return send(method, path, body, new int[0]);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String method, String path, Object body, int... acceptedStatuses)
            throws SearchException {
        String baseUrl = configuration.url();
        logger.log(Level.FINE, "Calling Meilisearch: {0} {1}", new Object[] { method, path });
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(stripTrailingSlash(baseUrl) + path))
                .timeout(configuration.requestTimeout())
                .header("Accept", "application/json");
        configuration.apiKey()
                .ifPresent(key -> request.header("Authorization", "Bearer " + key));

        try (Jsonb jsonb = JsonbBuilder.create()) {
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(jsonb.toJson(body)));
            }
            HttpResponse<String> response = httpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
            logger.log(Level.FINE, "Meilisearch returned HTTP {0} for {1} {2}",
                    new Object[] { response.statusCode(), method, path });
            if (java.util.Arrays.stream(acceptedStatuses).anyMatch(status -> status == response.statusCode())) {
                return Map.of();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.log(Level.WARNING, "Meilisearch request failed with HTTP {0} for {1} {2}",
                        new Object[] { response.statusCode(), method, path });
                throw new SearchException("Meilisearch returned HTTP " + response.statusCode() + ": "
                        + response.body(), null);
            }
            return jsonb.fromJson(response.body(), Map.class);
        } catch (SearchException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SearchException("Interrupted while calling Meilisearch", ex);
        } catch (Exception ex) {
            throw new SearchException("Unable to call Meilisearch", ex);
        }
    }

    private long taskUid(Map<String, Object> response) throws SearchException {
        Object value = response.get("taskUid");
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new SearchException("Meilisearch response did not contain a taskUid", null);
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

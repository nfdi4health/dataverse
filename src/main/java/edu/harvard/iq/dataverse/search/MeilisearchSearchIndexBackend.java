package edu.harvard.iq.dataverse.search;

import java.io.StringReader;
import java.util.concurrent.TimeUnit;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class MeilisearchSearchIndexBackend implements SearchIndexBackend {

    static final int TASK_POLL_ATTEMPTS = 120;
    static final long TASK_POLL_INTERVAL_MILLIS = 1000;

    @Override
    public SearchIndexOperation.Backend getBackend() {
        return SearchIndexOperation.Backend.MEILISEARCH;
    }

    @Override
    public void execute(SearchIndexOperation operation) throws Exception {
        String baseUrl = JvmSettings.MEILISEARCH_URL.lookupOptional()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        JvmSettings.MEILISEARCH_URL.getScopedKey() + " is not configured"));
        String index = JvmSettings.MEILISEARCH_INDEX.lookupOptional()
                .filter(value -> !value.isBlank())
                .orElse(MeilisearchSearchServiceBean.DEFAULT_INDEX);
        String apiKey = JvmSettings.MEILISEARCH_INDEX_API_KEY.lookupOptional()
                .filter(value -> !value.isBlank())
                .orElseGet(() -> JvmSettings.MEILISEARCH_API_KEY.lookupOptional().orElse(null));

        try (Client client = ClientBuilder.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()) {
            Response response = switch (operation.getOperationType()) {
                case UPSERT -> request(client, baseUrl, apiKey, "indexes", index, "documents")
                        .post(Entity.entity(operation.getPayload(), MediaType.APPLICATION_JSON_TYPE));
                case DELETE -> request(client, baseUrl, apiKey, "indexes", index, "documents", "delete-batch")
                        .post(Entity.entity(operation.getPayload(), MediaType.APPLICATION_JSON_TYPE));
                case DELETE_ALL -> request(client, baseUrl, apiKey, "indexes", index, "documents").delete();
            };
            int taskUid;
            try (response) {
                taskUid = readTaskUid(response);
            }
            waitForTask(client, baseUrl, apiKey, taskUid);
        }
    }

    private static Invocation.Builder request(Client client, String baseUrl, String apiKey, String... path) {
        var target = client.target(baseUrl);
        for (String segment : path) {
            target = target.path(segment);
        }
        Invocation.Builder request = target.request(MediaType.APPLICATION_JSON_TYPE);
        if (apiKey != null && !apiKey.isBlank()) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        return request;
    }

    private static int readTaskUid(Response response) {
        String body = response.hasEntity() ? response.readEntity(String.class) : "";
        if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            throw new IllegalStateException("Meilisearch returned HTTP " + response.getStatus()
                    + (body.isBlank() ? "" : ": " + body));
        }
        JsonObject task = parseObject(body);
        if (!task.containsKey("taskUid")) {
            throw new IllegalStateException("Meilisearch response does not contain taskUid: " + body);
        }
        return task.getInt("taskUid");
    }

    private static void waitForTask(Client client, String baseUrl, String apiKey, int taskUid) throws Exception {
        for (int attempt = 0; attempt < TASK_POLL_ATTEMPTS; attempt++) {
            try (Response response = request(client, baseUrl, apiKey, "tasks", Integer.toString(taskUid)).get()) {
                String body = response.hasEntity() ? response.readEntity(String.class) : "";
                if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                    throw new IllegalStateException("Meilisearch task query returned HTTP " + response.getStatus()
                            + (body.isBlank() ? "" : ": " + body));
                }
                JsonObject task = parseObject(body);
                String status = task.getString("status", "");
                if ("succeeded".equals(status)) {
                    return;
                }
                if ("failed".equals(status) || "canceled".equals(status)) {
                    throw new IllegalStateException("Meilisearch task " + taskUid + " " + status + ": " + body);
                }
            }
            Thread.sleep(TASK_POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("Timed out waiting for Meilisearch task " + taskUid);
    }

    private static JsonObject parseObject(String body) {
        try (var reader = Json.createReader(new StringReader(body))) {
            return reader.readObject();
        }
    }
}

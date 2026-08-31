package edu.harvard.iq.dataverse.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import edu.harvard.iq.dataverse.search.SearchIndexOperation.Backend;
import edu.harvard.iq.dataverse.search.SearchIndexOperation.OperationType;
import edu.harvard.iq.dataverse.search.SearchIndexOperation.State;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

class SearchIndexOperationTest {

    @Test
    void tracksRetryAndPermanentFailureState() {
        Timestamp now = Timestamp.from(Instant.parse("2026-08-31T09:00:00Z"));
        SearchIndexOperation operation = new SearchIndexOperation(Backend.MEILISEARCH, OperationType.UPSERT, "[]", now);

        operation.claim(Timestamp.from(now.toInstant().plusSeconds(60)));
        operation.retry(Timestamp.from(now.toInstant().plusSeconds(5)), "temporarily unavailable");

        assertEquals(State.RETRYING, operation.getState());
        assertEquals(1, operation.getAttemptCount());
        assertEquals("temporarily unavailable", operation.getLastError());

        operation.claim(Timestamp.from(now.toInstant().plusSeconds(60)));
        operation.failPermanently("still unavailable");

        assertEquals(State.DEAD, operation.getState());
        assertEquals(2, operation.getAttemptCount());
    }

    @Test
    void capsExponentialRetryDelay() {
        assertEquals(Duration.ofSeconds(5), SearchIndexOperationServiceBean.retryDelay(1));
        assertEquals(Duration.ofSeconds(300), SearchIndexOperationServiceBean.retryDelay(10));
    }

    @Test
    void marksAnOperationDeadAfterTenFailures() {
        SearchIndexOperation operation = new SearchIndexOperation(Backend.SOLR, OperationType.UPSERT, "[]",
                Timestamp.from(Instant.now()));
        EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.find(SearchIndexOperation.class, 1L, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(operation);
        SearchIndexOperationServiceBean service = new SearchIndexOperationServiceBean();
        service.entityManager = entityManager;

        for (int attempt = 0; attempt < SearchIndexOperationServiceBean.MAX_ATTEMPTS; attempt++) {
            operation.claim(Timestamp.from(Instant.now().plusSeconds(60)));
            service.recordFailure(1L, "unavailable");
        }

        assertEquals(SearchIndexOperationServiceBean.MAX_ATTEMPTS, operation.getAttemptCount());
        assertEquals(State.DEAD, operation.getState());
    }
}

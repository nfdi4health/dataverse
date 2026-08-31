package edu.harvard.iq.dataverse.search;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import edu.harvard.iq.dataverse.search.SearchIndexOperation.Backend;
import edu.harvard.iq.dataverse.search.SearchIndexOperation.OperationType;
import edu.harvard.iq.dataverse.search.SearchIndexOperation.State;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

@Stateless
public class SearchIndexOperationServiceBean {

    static final int MAX_ATTEMPTS = 10;
    static final Duration PROCESSING_LEASE = Duration.ofMinutes(5);
    private static final int MAX_ERROR_LENGTH = 10_000;

    @PersistenceContext(unitName = "VDCNet-ejbPU")
    EntityManager entityManager;

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void enqueueContent(OperationType operationType, String payload) {
        Timestamp now = Timestamp.from(Instant.now());
        entityManager.persist(new SearchIndexOperation(Backend.SOLR, operationType, payload, now));
        if (isMeilisearchConfigured()) {
            entityManager.persist(new SearchIndexOperation(Backend.MEILISEARCH, operationType, payload, now));
        }
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void enqueueSolr(OperationType operationType, String payload) {
        Timestamp now = Timestamp.from(Instant.now());
        entityManager.persist(new SearchIndexOperation(Backend.SOLR, operationType, payload, now));
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public Optional<SearchIndexOperation> claimNext(Backend backend) {
        List<SearchIndexOperation> operations = entityManager.createQuery(
                        "select operation from SearchIndexOperation operation "
                                + "where operation.backend = :backend order by operation.id",
                        SearchIndexOperation.class)
                .setParameter("backend", backend)
                .setMaxResults(1)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
        if (operations.isEmpty()) {
            return Optional.empty();
        }

        SearchIndexOperation operation = operations.get(0);
        Instant now = Instant.now();
        if (operation.getState() == State.DEAD
                || operation.getState() == State.RETRYING && operation.getNextAttemptAt().toInstant().isAfter(now)
                || operation.getState() == State.PROCESSING && operation.getLeaseUntil() != null
                        && operation.getLeaseUntil().toInstant().isAfter(now)) {
            return Optional.empty();
        }
        if (operation.getAttemptCount() >= MAX_ATTEMPTS) {
            operation.failPermanently("Processing lease expired after the final delivery attempt");
            return Optional.empty();
        }

        operation.claim(Timestamp.from(now.plus(PROCESSING_LEASE)));
        entityManager.flush();
        return Optional.of(operation);
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void complete(long operationId) {
        SearchIndexOperation operation = entityManager.find(
                SearchIndexOperation.class, operationId, LockModeType.PESSIMISTIC_WRITE);
        if (operation != null && operation.getState() == State.PROCESSING) {
            entityManager.remove(operation);
        }
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void recordFailure(long operationId, String error) {
        SearchIndexOperation operation = entityManager.find(
                SearchIndexOperation.class, operationId, LockModeType.PESSIMISTIC_WRITE);
        if (operation == null || operation.getState() != State.PROCESSING) {
            return;
        }

        String safeError = truncate(error);
        if (operation.getAttemptCount() >= MAX_ATTEMPTS) {
            operation.failPermanently(safeError);
        } else {
            Instant retryAt = Instant.now().plus(retryDelay(operation.getAttemptCount()));
            operation.retry(Timestamp.from(retryAt), safeError);
        }
    }

    public Map<State, Long> getStatus(Backend backend) {
        Map<State, Long> status = new EnumMap<>(State.class);
        for (State state : State.values()) {
            status.put(state, entityManager.createQuery(
                            "select count(operation) from SearchIndexOperation operation "
                                    + "where operation.backend = :backend and operation.state = :state",
                            Long.class)
                    .setParameter("backend", backend)
                    .setParameter("state", state)
                    .getSingleResult());
        }
        return status;
    }

    public boolean isConfigured(Backend backend) {
        return backend == Backend.SOLR || isMeilisearchConfigured();
    }

    public Optional<SearchIndexOperation> getBlockingOperation(Backend backend) {
        List<SearchIndexOperation> operations = entityManager.createQuery(
                        "select operation from SearchIndexOperation operation "
                                + "where operation.backend = :backend order by operation.id",
                        SearchIndexOperation.class)
                .setParameter("backend", backend)
                .setMaxResults(1)
                .getResultList();
        return operations.stream().findFirst();
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public int resume(Backend backend) {
        Timestamp now = Timestamp.from(Instant.now());
        List<SearchIndexOperation> operations = entityManager.createQuery(
                        "select operation from SearchIndexOperation operation "
                                + "where operation.backend = :backend and operation.state = :state",
                        SearchIndexOperation.class)
                .setParameter("backend", backend)
                .setParameter("state", State.DEAD)
                .getResultList();
        operations.forEach(operation -> operation.resume(now));
        return operations.size();
    }

    static Duration retryDelay(int attemptCount) {
        long seconds = Math.min(300, 5L << Math.min(attemptCount - 1, 6));
        return Duration.ofSeconds(seconds);
    }

    private static boolean isMeilisearchConfigured() {
        return JvmSettings.MEILISEARCH_URL.lookupOptional().filter(value -> !value.isBlank()).isPresent();
    }

    private static String truncate(String error) {
        String value = error == null ? "Unknown index backend failure" : error;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}

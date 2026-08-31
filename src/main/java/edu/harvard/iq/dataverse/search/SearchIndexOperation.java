package edu.harvard.iq.dataverse.search;

import java.io.Serializable;
import java.sql.Timestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "searchindexoperation")
public class SearchIndexOperation implements Serializable {

    public enum Backend {
        SOLR,
        MEILISEARCH
    }

    public enum OperationType {
        UPSERT,
        DELETE,
        DELETE_ALL
    }

    public enum State {
        PENDING,
        PROCESSING,
        RETRYING,
        DEAD
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Backend backend;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OperationType operationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private State state;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private Timestamp createdAt;

    @Column(nullable = false)
    private Timestamp nextAttemptAt;

    private Timestamp leaseUntil;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    protected SearchIndexOperation() {
    }

    SearchIndexOperation(Backend backend, OperationType operationType, String payload, Timestamp now) {
        this.backend = backend;
        this.operationType = operationType;
        this.payload = payload;
        state = State.PENDING;
        createdAt = now;
        nextAttemptAt = now;
    }

    public Long getId() {
        return id;
    }

    public Backend getBackend() {
        return backend;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public State getState() {
        return state;
    }

    public String getPayload() {
        return payload;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public Timestamp getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Timestamp getLeaseUntil() {
        return leaseUntil;
    }

    public String getLastError() {
        return lastError;
    }

    void claim(Timestamp leaseUntil) {
        attemptCount++;
        state = State.PROCESSING;
        this.leaseUntil = leaseUntil;
    }

    void retry(Timestamp nextAttemptAt, String error) {
        state = State.RETRYING;
        this.nextAttemptAt = nextAttemptAt;
        leaseUntil = null;
        lastError = error;
    }

    void failPermanently(String error) {
        state = State.DEAD;
        leaseUntil = null;
        lastError = error;
    }

    void resume(Timestamp now) {
        attemptCount = 0;
        state = State.PENDING;
        nextAttemptAt = now;
        leaseUntil = null;
        lastError = null;
    }
}

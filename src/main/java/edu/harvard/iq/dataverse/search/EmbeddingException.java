package edu.harvard.iq.dataverse.search;

/** A safe-to-report embedding failure, without provider payloads or underlying causes. */
public class EmbeddingException extends Exception {

    public EmbeddingException(String message) {
        super(message);
    }
}

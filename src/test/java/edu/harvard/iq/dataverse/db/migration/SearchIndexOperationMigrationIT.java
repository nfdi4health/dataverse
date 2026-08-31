package edu.harvard.iq.dataverse.db.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import edu.harvard.iq.dataverse.util.testing.Tags;

@Tag(Tags.DB_MIGRATION_TEST)
@Tag(Tags.USES_TESTCONTAINERS)
@Testcontainers(disabledWithoutDocker = true)
class SearchIndexOperationMigrationIT {

    private static final String MIGRATION_RESOURCE =
            "/db/migration/V6.11.0.1__search-index-operation-queue.sql";

    @BeforeEach
    void createTable() throws Exception {
        dropTable();
        DBUnitHelper.runMigrationScript(MIGRATION_RESOURCE);
    }

    @AfterEach
    void dropTable() throws Exception {
        try (Connection connection = SharedPostgresContainer.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS searchindexoperation");
        }
    }

    @Test
    void createsQueueTableWithRetryDefaultsAndOrderingIndex() throws Exception {
        try (Connection connection = SharedPostgresContainer.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO searchindexoperation
                        (backend, operationtype, state, payload, createdat, nextattemptat)
                    VALUES
                        ('SOLR', 'UPSERT', 'PENDING', '[]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);

            try (ResultSet result = statement.executeQuery(
                    "SELECT version, attemptcount FROM searchindexoperation")) {
                assertTrue(result.next());
                assertEquals(0L, result.getLong("version"));
                assertEquals(0, result.getInt("attemptcount"));
            }
            try (ResultSet result = statement.executeQuery("""
                    SELECT COUNT(*)
                    FROM pg_indexes
                    WHERE tablename = 'searchindexoperation'
                      AND indexname = 'index_searchindexoperation_backend_id'
                    """)) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }
}

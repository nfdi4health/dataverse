package edu.harvard.iq.dataverse.api;

import org.junit.jupiter.api.Test;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.TermsOfUseAndAccess;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class DatasetsTest {

    @Test
    public void testIsDatasetVersionNoOp() {
        DatasetVersion v1 = new DatasetVersion();
        DatasetVersion v2 = new DatasetVersion();

        assertTrue(Datasets.isDatasetVersionNoOp(v1, v2));

        TermsOfUseAndAccess toua1 = new TermsOfUseAndAccess();
        toua1.setFileAccessRequest(true);
        v1.setTermsOfUseAndAccess(toua1);

        TermsOfUseAndAccess toua2 = new TermsOfUseAndAccess();
        toua2.setFileAccessRequest(false);
        v2.setTermsOfUseAndAccess(toua2);

        assertFalse(Datasets.isDatasetVersionNoOp(v1, v2));
    }

    /**
     * Test cleanup filter
     */
    @Test
    public void testCleanup() {
        Set<String> datasetFiles = new HashSet<>() {
            {
                add("1837fda0b6c-90779481d439");
                add("1837fda0e17-4b0926f6d44e");
                add("1837fda1b80-46a899909269");
            }
        };
        Set<String> filesOnDrive = new HashSet<>() {
            {
                add("1837fda0b6c-90779481d439");
                add("1837fda0e17-4b0926f6d44e");
                add("1837fda1b80-46a899909269");
                add("prefix_1837fda0b6c-90779481d439");
                add("1837fda0e17-4b0926f6d44e_suffix");
                add("1837fda1b80-extra-46a899909269");
                add("1837fda0e17-4b0926f6d44e.aux");
                add("1837fda1994-5f74d57e6e47");
                add("1837fda17ce-d7b9987fc6e9");
                add("18383198c49-aeda08ccffff");
                add("prefix_1837fda1994-5f74d57e6e47");
                add("1837fda17ce-d7b9987fc6e9_suffix");
                add("18383198c49-extra-aeda08ccffff");
                add("some_other_file");
                add("1837fda17ce-d7b9987fc6e9.aux");
                add("18383198c49.aeda08ccffff");
                add("1837fda17ce-d7b9987fc6xy");
            }
        };

        Predicate<String> toDeleteFilesFilter = Datasets.getToDeleteFilesFilter(datasetFiles);
        Set<String> deleted = filesOnDrive.stream().filter(toDeleteFilesFilter).collect(Collectors.toSet());

        assertEquals(5, deleted.size());
        assertTrue(deleted.contains("1837fda1994-5f74d57e6e47"));
        assertTrue(deleted.contains("1837fda17ce-d7b9987fc6e9"));
        assertTrue(deleted.contains("18383198c49-aeda08ccffff"));
        assertTrue(deleted.contains("1837fda17ce-d7b9987fc6e9_suffix"));
        assertTrue(deleted.contains("1837fda17ce-d7b9987fc6e9.aux"));
    }
}

package edu.harvard.iq.dataverse.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.solr.client.solrj.SolrQuery;
import org.junit.jupiter.api.Test;

import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;

class MeilisearchSearchServiceBeanTest {

    @Test
    void recognizesSupportedAndUnsupportedQueries() {
        assertTrue(MeilisearchSearchServiceBean.supportsQuery("Darwin finches"));
        assertTrue(MeilisearchSearchServiceBean.supportsQuery("\"Darwin finches\" -sparrow"));
        assertTrue(MeilisearchSearchServiceBean.supportsQuery("*"));
        assertFalse(MeilisearchSearchServiceBean.supportsQuery("title:finches"));
        assertFalse(MeilisearchSearchServiceBean.supportsQuery("finches AND sparrows"));
        assertFalse(MeilisearchSearchServiceBean.supportsQuery("date:[2020 TO 2024]"));
        assertFalse(MeilisearchSearchServiceBean.supportsQuery("\"unterminated"));
    }

    @Test
    void usesMeilisearchOnlyForDatasetCompatibleTypeFilters() {
        assertFalse(MeilisearchSearchServiceBean.requiresNonDatasetResults(List.of()));
        assertFalse(MeilisearchSearchServiceBean.requiresNonDatasetResults(List.of("dvObjectType:(datasets)")));
        assertTrue(MeilisearchSearchServiceBean.requiresNonDatasetResults(List.of("dvObjectType:(files)")));
        assertTrue(MeilisearchSearchServiceBean.requiresNonDatasetResults(
                List.of("dvObjectType:(dataverses OR datasets OR files)")));
    }

    @Test
    void buildsEscapedCandidateQuery() {
        assertEquals("dsPersistentId:(\"doi:10.1/first\" OR \"hdl:quoted\\\"value\")",
                MeilisearchSearchServiceBean.buildCandidateQuery(
                        List.of("doi:10.1/first", "hdl:quoted\"value")));
    }

    @Test
    void hydratesWithSolrThenRanksAndPaginates() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                new MeilisearchClient.SearchHit("doi:10.1/first", 0.9F),
                new MeilisearchClient.SearchHit("doi:10.1/second", 0.8F)));
        RecordingSearchService solr = new RecordingSearchService(response(List.of(
                result("doi:10.1/second"), result("doi:10.1/first"))));
        MeilisearchSearchServiceBean service = service(client, solr);

        SolrQueryResponse response = service.search(null, null, "finches",
                List.of("publicationStatus:Published"), SearchFields.RELEVANCE, SortBy.DESCENDING,
                1, false, 1, false, null, null, true, true, false);

        assertEquals(MeilisearchSearchServiceBean.DATASET_FILTER, client.request.filter());
        assertEquals(List.of("doi:10.1/second"), response.getSolrSearchResults().stream()
                .map(SolrSearchResult::getIdentifier).toList());
        assertEquals(0.8F, response.getSolrSearchResults().getFirst().getScore());
        assertEquals(2L, response.getNumResultsFound());
        assertEquals(1L, response.getResultsStart());
        assertTrue(solr.query.startsWith("dsPersistentId:("));
        assertEquals(0, solr.paginationStart);
        assertEquals(4, solr.numResultsPerPage);
        assertFalse(solr.addHighlights);
    }

    @Test
    void delegatesMixedTypeQueriesDirectlyToSolr() throws Exception {
        SolrQueryResponse expected = response(List.of());
        RecordingClient client = new RecordingClient(List.of());
        RecordingSearchService solr = new RecordingSearchService(expected);
        MeilisearchSearchServiceBean service = service(client, solr);

        SolrQueryResponse actual = service.search(null, null, "finches",
                List.of("dvObjectType:(dataverses OR datasets OR files)"), SearchFields.RELEVANCE,
                SortBy.DESCENDING, 3, false, 10, false, null, null, true, true, false);

        assertSame(expected, actual);
        assertEquals("finches", solr.query);
        assertEquals(3, solr.paginationStart);
        assertEquals(null, client.request);
    }

    private static MeilisearchSearchServiceBean service(RecordingClient client, SearchService solr) {
        MeilisearchSearchServiceBean service = new MeilisearchSearchServiceBean();
        service.meilisearchClient = client;
        service.configuration = new MeilisearchConfiguration();
        service.setSolrSearchService(solr);
        return service;
    }

    private static SolrSearchResult result(String pid) {
        SolrSearchResult result = new SolrSearchResult("finches", pid);
        result.setIdentifier(pid);
        return result;
    }

    private static SolrQueryResponse response(List<SolrSearchResult> results) {
        SolrQueryResponse response = new SolrQueryResponse(new SolrQuery("test"));
        response.setSolrSearchResults(results);
        response.setNumResultsFound((long) results.size());
        response.setResultsStart(0L);
        response.setSpellingSuggestionsByToken(Map.of());
        response.setFacetCategoryList(List.of());
        response.setTypeFacetCategories(List.of());
        return response;
    }

    private static class RecordingClient implements MeilisearchClient {
        private final List<SearchHit> hits;
        private SearchRequest request;

        private RecordingClient(List<SearchHit> hits) {
            this.hits = hits;
        }

        @Override public boolean indexExists() { return true; }
        @Override public long createIndex(String primaryKey) { return 0; }
        @Override public Map<String, Object> getSettings() { return Map.of(); }
        @Override public long updateSettings(Map<String, Object> settings) { return 0; }

        @Override
        public SearchResult search(SearchRequest request) {
            this.request = request;
            return new SearchResult(hits);
        }

        @Override public long addDocuments(List<Map<String, Object>> documents) { return 0; }
        @Override public long deleteDocuments(java.util.Collection<String> ids) { return 0; }
        @Override public long deleteAllDocuments() { return 0; }
        @Override public Task getTask(long taskId) { return new Task(taskId, "succeeded", null); }
    }

    private static class RecordingSearchService implements SearchService {
        private final SolrQueryResponse response;
        private String query;
        private int paginationStart;
        private int numResultsPerPage;
        private boolean addHighlights;

        private RecordingSearchService(SolrQueryResponse response) {
            this.response = response;
        }

        @Override public String getServiceName() { return "recording"; }
        @Override public String getDisplayName() { return "Recording"; }

        @Override
        public SolrQueryResponse search(DataverseRequest request, List<Dataverse> dataverses, String query,
                List<String> filters, String sortField, String sortOrder, int start, boolean onlyMine, int rows,
                boolean retrieveEntities, String geoPoint, String geoRadius, boolean facets, boolean highlights,
                boolean collections) {
            this.query = query;
            this.paginationStart = start;
            this.numResultsPerPage = rows;
            this.addHighlights = highlights;
            return response;
        }
    }
}

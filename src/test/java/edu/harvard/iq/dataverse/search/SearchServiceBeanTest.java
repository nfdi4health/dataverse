package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetFieldConstant;
import edu.harvard.iq.dataverse.DatasetFieldServiceBean;
import edu.harvard.iq.dataverse.DatasetFieldType;
import edu.harvard.iq.dataverse.DvObjectServiceBean;
import edu.harvard.iq.dataverse.ThumbnailServiceWrapper;
import edu.harvard.iq.dataverse.authorization.groups.Group;
import edu.harvard.iq.dataverse.authorization.groups.GroupServiceBean;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.authorization.users.GuestUser;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.search.SearchServiceBean.RetrievalMode;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.SystemConfig;
import edu.harvard.iq.dataverse.util.testing.JvmSetting;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.BaseHttpSolrClient.RemoteSolrException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@LocalJvmSettings
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@JvmSetting(key = JvmSettings.FEATURE_FLAG, varArgs = "avoid-expensive-solr-join", value = "false")
class SearchServiceBeanTest {

    private static final String SOLR_URL = "http://solr.invalid/solr/collection1";
    private static final String QUERY = "  ocean: observations AND currents 😀  ";
    private static final String MODEL = "model 'quoted' {!term} identity";
    private static final String PIPELINE = "pipeline:with spaces/$parameters";
    private static final String TITLE_FIELD = "title";
    private static final int TOP_K = 40;
    private static final long DATASET_ID = 42L;
    private static final long VERSION_ID = 123L;
    private static final String DOCUMENT_ID = "dataset_42";

    private SearchServiceBean service;
    private SolrClient client;
    private QueryResponse response;
    private SolrDocumentList documents;
    private DataverseRequest request;

    @BeforeEach
    void setUp() throws Exception {
        service = new SearchServiceBean();
        service.solrClientService = mock(SolrClientService.class);
        service.queryEmbeddingService = mock(QueryEmbeddingService.class);
        service.datasetFieldService = mock(DatasetFieldServiceBean.class);
        service.groupService = mock(GroupServiceBean.class);
        service.systemConfig = mock(SystemConfig.class);
        service.dvObjectService = mock(DvObjectServiceBean.class);
        service.thumbnailServiceWrapper = mock(ThumbnailServiceWrapper.class);
        request = new DataverseRequest(GuestUser.get(), (jakarta.servlet.http.HttpServletRequest) null);
        client = mock(SolrClient.class);
        response = mock(QueryResponse.class);
        when(service.solrClientService.getSolrClient()).thenReturn(client);
        when(service.solrClientService.getSolrUrl()).thenReturn(SOLR_URL);
        when(client.query(any(SolrQuery.class))).thenReturn(response);
        when(service.queryEmbeddingService.embed(anyString(), eq(SOLR_URL))).thenReturn(embedding(TOP_K));
        DatasetFieldType title = mock(DatasetFieldType.class);
        SolrField titleSolrField = mock(SolrField.class);
        when(title.getSolrField()).thenReturn(titleSolrField);
        when(titleSolrField.getNameSearchable()).thenReturn(TITLE_FIELD);
        when(title.getDisplayName()).thenReturn("Title");
        when(service.datasetFieldService.findAllOrderedById()).thenReturn(List.of(title));
        when(service.datasetFieldService.findByName(DatasetFieldConstant.title)).thenReturn(title);
        when(service.groupService.groupsFor(request)).thenReturn(Set.of());
        when(service.groupService.collectAncestors(anyCollection())).thenReturn(Set.of());
        when(service.systemConfig.getDataverseSiteUrl()).thenReturn("https://dataverse.invalid");

        documents = new SolrDocumentList();
        SolrDocument document = new SolrDocument();
        document.setField(SearchFields.ID, DOCUMENT_ID);
        document.setField(SearchFields.ENTITY_ID, DATASET_ID);
        document.setField(SearchFields.TYPE, SearchConstants.DATASETS);
        document.setField(SearchFields.RELEVANCE, 0.875f);
        document.setField(SearchFields.DATASET_VERSION_ID, VERSION_ID);
        document.setField(SearchFields.IDENTIFIER, "doi:10.5072/OCEAN");
        document.setField(TITLE_FIELD, "Ocean observations");
        document.setField(SearchFields.DATASET_DESCRIPTION, List.of("First description.", "Second description."));
        document.setField(SearchFields.PUBLICATION_STATUS, List.of(IndexServiceBean.PUBLISHED_STRING));
        documents.add(document);
        documents.setNumFound(17);
        documents.setStart(5);
        when(response.getResults()).thenReturn(documents);
        FacetField types = facet(SearchFields.TYPE, SearchConstants.DATASETS, 17);
        FacetField years = facet(SearchFields.PUBLICATION_YEAR, "2024", 12);
        FacetField statuses = facet(SearchFields.PUBLICATION_STATUS, IndexServiceBean.PUBLISHED_STRING, 17);
        when(response.getFacetFields()).thenReturn(List.of(types, years, statuses));
        when(response.getFacetRanges()).thenReturn(List.of());
        when(response.getFacetField(SearchFields.TYPE)).thenReturn(types);
        when(response.getFacetField(SearchFields.PUBLICATION_STATUS)).thenReturn(statuses);
    }

    @Test
    void constructsTopLevelKnnWithParameterizedProvenanceAndDeterministicScoreSort() throws Exception {
        SolrQueryResponse result = semantic(5, 10, false);
        SolrQuery query = capturedQuery();

        assertEquals("{!knn f=" + SearchFields.DATASET_EMBEDDING + " topK=40}[1.0,-2.5,0.25]", query.getQuery());
        assertEquals("lucene", query.get("defType"));
        assertEquals(List.of(SolrQuery.SortClause.desc(SearchFields.RELEVANCE),
                SolrQuery.SortClause.asc(SearchFields.ID)), query.getSorts());
        assertEquals(MODEL, query.get("semanticModel"));
        assertEquals(PIPELINE, query.get("semanticPipeline"));
        List<String> filters = Arrays.asList(query.getFilterQueries());
        assertTrue(filters.contains("{!term f=" + SearchFields.EMBEDDER + " v=$semanticModel}"));
        assertTrue(filters.contains("{!term f=" + SearchFields.EMBEDDING_PIPELINE + " v=$semanticPipeline}"));
        assertTrue(filters.contains("{!term f=" + SearchFields.TYPE + "}" + SearchConstants.DATASETS));
        assertTrue(filters.contains("{!term f=" + SearchFields.PUBLICATION_STATUS + "}" + IndexServiceBean.PUBLISHED_STRING));
        assertEquals("*,score", query.get("fl"));
        assertEquals("/select", query.get("qt"));
        assertEquals("none", query.get("echoParams"));
        assertEquals("qt,start,rows", query.get("logParamsList"));
        assertEquals("false", query.get("shards.tolerant"));
        assertSame(query, result.getSolrQuery());
        verify(service.queryEmbeddingService).embed(QUERY, SOLR_URL);
        verifyNoMoreInteractions(service.queryEmbeddingService);
    }

    @ParameterizedTest
    @CsvSource({"0, 3", "9, 7", "30, 10", "0, 40"})
    void configuredCandidateWindowDoesNotDependOnPageOrRows(int start, int rows) throws Exception {
        semantic(start, rows, false);
        SolrQuery query = capturedQuery();
        assertTrue(query.getQuery().contains(" topK=" + TOP_K + "}"));
        assertEquals(start, query.getStart().intValue());
        assertEquals(rows, query.getRows().intValue());
    }

    @Test
    void retainsGuestGroupsUserFiltersSubtreeAndGeoFilters() throws Exception {
        Group group = mock(Group.class);
        when(group.getAlias()).thenReturn("ip/campus");
        Set<Group> groups = Set.of(group);
        when(service.groupService.groupsFor(request)).thenReturn(groups);
        when(service.groupService.collectAncestors(groups)).thenReturn(groups);
        List<String> userFilters = List.of(SearchFields.SUBTREE + ":\"/research\"", "subject_ss:\"Oceans\"");

        service.search(request, List.of(), QUERY, userFilters, SearchFields.NAME_SORT, "asc",
                0, false, 10, false, "35,15", "5", true, true, RetrievalMode.SEMANTIC);
        SolrQuery query = capturedQuery();
        List<String> filters = Arrays.asList(query.getFilterQueries());
        assertTrue(filters.containsAll(userFilters));
        assertTrue(filters.contains(permissionJoin()));
        assertEquals(SearchFields.DISCOVERABLE_BY + ":(" + IndexServiceBean.getPublicGroupString()
                + " OR " + IndexServiceBean.getGroupPrefix() + "ip/campus)", query.get("q1"));
        assertTrue(filters.contains("{!bbox sfield=" + SearchFields.GEOLOCATION + "}"));
        assertEquals("35,15", query.get("pt"));
        assertEquals("5", query.get("d"));
        assertFalse(filters.stream().anyMatch(filter -> filter.contains("&q1=")));
        verify(service.groupService).groupsFor(request);
        verify(service.groupService).collectAncestors(groups);
    }

    @Test
    void authenticatedPermissionQueryIncludesPublicUserAndAncestorGroup() throws Exception {
        AuthenticatedUser user = mock(AuthenticatedUser.class);
        when(user.getId()).thenReturn(7L);
        request = new DataverseRequest(user, (jakarta.servlet.http.HttpServletRequest) null);
        Group ancestor = mock(Group.class);
        when(ancestor.getAlias()).thenReturn("explicit/parent");
        when(service.groupService.collectAncestors(anyCollection())).thenReturn(Set.of(ancestor));

        semantic(0, 10, false);
        SolrQuery query = capturedQuery();
        assertTrue(Arrays.asList(query.getFilterQueries()).contains(permissionJoin()));
        assertEquals(SearchFields.DISCOVERABLE_BY + ":(" + IndexServiceBean.getPublicGroupString()
                + " OR " + IndexServiceBean.getGroupPerUserPrefix() + "7 OR "
                + IndexServiceBean.getGroupPrefix() + "explicit/parent)", query.get("q1"));
    }

    @Test
    void mapsDocumentsFacetsAndTypeCountsFromOneSemanticResponseWithoutLexicalFeatures() throws Exception {
        SolrQueryResponse result = semantic(5, 10, false);
        SolrQuery query = capturedQuery();
        assertEquals("false", query.get("hl"));
        assertEquals("false", query.get("spellcheck"));
        assertNull(query.getHighlightFields());
        verify(response, never()).getHighlighting();
        verify(response, never()).getSpellCheckResponse();
        assertTrue(result.getSpellingSuggestionsByToken().isEmpty());
        assertEquals(17L, result.getNumResultsFound().longValue());
        assertEquals(5L, result.getResultsStart().longValue());
        assertEquals(Map.of(SolrQueryResponse.DATASETS_COUNT_KEY, 17L), result.getDvObjectCounts());
        assertEquals(Map.of("published_count", 17L), result.getPublicationStatusCounts());
        assertTrue(Arrays.asList(query.getFacetFields()).containsAll(List.of(
                SearchFields.TYPE, SearchFields.PUBLICATION_YEAR, SearchFields.PUBLICATION_STATUS)));
        assertEquals(1, result.getTypeFacetCategories().size());
        assertFacet(result.getTypeFacetCategories().get(0), SearchFields.TYPE, SearchConstants.DATASETS, 17);
        assertEquals(1, result.getFacetCategoryList().size());
        assertFacet(result.getFacetCategoryList().get(0), SearchFields.PUBLICATION_YEAR, "2024", 12);
        assertEquals(Arrays.asList(query.getFilterQueries()), result.getFilterQueriesActual());
        assertMappedDataset(result);
        assertNull(result.getSolrSearchResults().get(0).getEntity());
        verifyNoInteractions(service.dvObjectService);
    }

    @Test
    void retrievesDatasetThroughExistingEntityHydrationWhenRequested() throws Exception {
        Dataset dataset = mock(Dataset.class);
        when(service.dvObjectService.findDvObject(DATASET_ID)).thenReturn(dataset);
        SolrQueryResponse result = semantic(0, 10, true);
        assertMappedDataset(result);
        assertSame(dataset, result.getSolrSearchResults().get(0).getEntity());
        verify(service.dvObjectService).findDvObject(DATASET_ID);
        verifyNoMoreInteractions(service.dvObjectService);
        capturedQuery();
    }

    @Test
    void typeFacetIsStillRequestedWhenOptionalFacetsAreDisabled() throws Exception {
        service.search(request, List.of(), QUERY, List.of(), null, null, 0, false,
                10, false, null, null, false, false, RetrievalMode.SEMANTIC);
        assertArrayEquals(new String[]{SearchFields.TYPE}, capturedQuery().getFacetFields());
    }

    @ParameterizedTest
    @CsvSource({"39, 2", "40, 1", "2147483647, 1", "1, 2147483647"})
    void rejectsPagesOutsideConfiguredCandidateWindow(int start, int rows) throws Exception {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> semantic(start, rows, false));
        assertEquals("Requested page exceeds the semantic candidate window", failure.getMessage());
        verify(service.queryEmbeddingService).embed(QUERY, SOLR_URL);
        verifyNoInteractions(client);
    }

    @Test
    void candidateWindowArithmeticDoesNotOverflowEvenAtMaximumTopK() throws Exception {
        when(service.queryEmbeddingService.embed(QUERY, SOLR_URL)).thenReturn(embedding(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> semantic(Integer.MAX_VALUE - 5, 10, false));
        verifyNoInteractions(client);
        semantic(Integer.MAX_VALUE - 5, 5, false);
        SolrQuery query = capturedQuery();
        assertTrue(query.getQuery().contains("topK=2147483647}"));
        assertEquals(Integer.MAX_VALUE - 5, query.getStart().intValue());
        assertEquals(5, query.getRows().intValue());
    }

    @ParameterizedTest
    @CsvSource({"-1, 10", "-2147483648, 10", "0, 0", "0, -1", "0, -2147483648"})
    void rejectsInvalidStartOrRowsBeforeEmbeddingOrSolr(int start, int rows) {
        for (RetrievalMode mode : RetrievalMode.values()) {
            assertThrows(IllegalArgumentException.class, () -> service.search(request, List.of(), QUERY,
                    List.of(), null, null, start, false, rows, false, null, null, true, true, mode));
        }
        verifyNoInteractions(service.queryEmbeddingService, service.solrClientService, client);
    }

    @Test
    void rejectsShowMyDataNullModeAndBlankSemanticQueriesBeforeAnyRetrieval() {
        assertThrows(IllegalArgumentException.class, () -> service.search(request, List.of(), QUERY,
                List.of(), null, null, 0, true, 10, false, null, null, true, true, RetrievalMode.SEMANTIC));
        assertThrows(IllegalArgumentException.class, () -> service.search(request, List.of(), QUERY,
                List.of(), null, null, 0, false, 10, false, null, null, true, true, null));
        for (String query : new String[]{null, "", " ", "\t\n\u2003"}) {
            assertThrows(IllegalArgumentException.class, () -> service.search(request, List.of(), query,
                    List.of(), null, null, 0, false, 10, false, null, null, true, true, RetrievalMode.SEMANTIC));
        }
        verifyNoInteractions(service.queryEmbeddingService, service.solrClientService, client);
    }

    @Test
    void preservesSanitizedEmbeddingFailureAsCauseWithoutKeywordFallback() throws Exception {
        EmbeddingException cause = new EmbeddingException("Semantic search is not enabled.");
        when(service.queryEmbeddingService.embed(QUERY, SOLR_URL)).thenThrow(cause);
        SearchException failure = assertThrows(SearchException.class, () -> semantic(0, 10, false));
        assertEquals(cause.getMessage(), failure.getMessage());
        assertSame(cause, failure.getCause());
        verify(service.queryEmbeddingService).embed(QUERY, SOLR_URL);
        verifyNoMoreInteractions(service.queryEmbeddingService);
        verifyNoInteractions(client, service.dvObjectService);
    }

    @ParameterizedTest
    @MethodSource("invalidEmbeddings")
    void rejectsInvalidEmbeddingResultsWithoutIssuingSolrQuery(QueryEmbeddingService.Result embedding) throws Exception {
        when(service.queryEmbeddingService.embed(QUERY, SOLR_URL)).thenReturn(embedding);
        SearchException failure = assertThrows(SearchException.class, () -> semantic(0, 1, false));
        assertEquals("Invalid semantic query embedding result.", failure.getMessage());
        assertNull(failure.getCause());
        verifyNoInteractions(client);
    }

    static Stream<QueryEmbeddingService.Result> invalidEmbeddings() {
        return Stream.of(
                embedding(0), embedding(-1),
                new QueryEmbeddingService.Result(List.of(), MODEL, PIPELINE, TOP_K),
                new QueryEmbeddingService.Result(List.of(Float.NaN), MODEL, PIPELINE, TOP_K),
                new QueryEmbeddingService.Result(List.of(Float.POSITIVE_INFINITY), MODEL, PIPELINE, TOP_K),
                new QueryEmbeddingService.Result(List.of(Float.NEGATIVE_INFINITY), MODEL, PIPELINE, TOP_K),
                new QueryEmbeddingService.Result(List.of(1f), " ", PIPELINE, TOP_K),
                new QueryEmbeddingService.Result(List.of(1f), MODEL, "\t", TOP_K));
    }

    @ParameterizedTest
    @MethodSource("solrFailures")
    void sanitizesSolrFailuresWithoutRetainingDetailsOrFallingBack(Exception cause) throws Exception {
        when(client.query(any(SolrQuery.class))).thenThrow(cause);
        SearchException failure = assertThrows(SearchException.class, () -> semantic(0, 10, false));
        assertEquals("Semantic search engine unavailable.", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        capturedQuery();
        verifyNoMoreInteractions(client);
        verifyNoInteractions(service.dvObjectService);
    }

    static Stream<Exception> solrFailures() {
        String privateDetails = "private query [1.0,-2.5,0.25] token=secret http://private.invalid";
        return Stream.of(new RemoteSolrException("private.invalid", 400, privateDetails, null),
                new RemoteSolrException("private.invalid", 503, privateDetails, null),
                new SolrServerException(privateDetails), new IOException(privateDetails));
    }

    @ParameterizedTest
    @CsvSource({"title:oceans AND currents, title:oceans AND currents", "doi:10.5072/OCEAN, doi\\:10.5072/OCEAN"})
    void existingFullOverloadRetainsKeywordQuerySortHighlightingAndSpelling(String input, String expected) throws Exception {
        when(response.getHighlighting()).thenReturn(Map.of(DOCUMENT_ID,
                Map.of(TITLE_FIELD, List.of("<em>Ocean</em> observations"))));
        SpellCheckResponse spelling = mock(SpellCheckResponse.class);
        SpellCheckResponse.Suggestion suggestion = mock(SpellCheckResponse.Suggestion.class);
        when(suggestion.getToken()).thenReturn("ocen");
        when(suggestion.getAlternatives()).thenReturn(List.of("ocean"));
        when(spelling.getSuggestions()).thenReturn(List.of(suggestion));
        when(response.getSpellCheckResponse()).thenReturn(spelling);
        List<String> filters = List.of(SearchFields.SUBTREE + ":\"/research\"");

        SolrQueryResponse result = service.search(request, List.of(), input, filters, SearchFields.NAME_SORT,
                "asc", 5, false, 10, false, "35,15", "5", true, true);
        SolrQuery query = capturedQuery();
        assertEquals(expected, query.getQuery());
        assertEquals(List.of(SolrQuery.SortClause.asc(SearchFields.NAME_SORT)), query.getSorts());
        assertNull(query.get("defType"));
        assertNull(query.get("semanticModel"));
        assertNull(query.get("semanticPipeline"));
        assertNull(query.get("spellcheck"));
        assertEquals("true", query.get("hl"));
        assertTrue(Arrays.asList(query.getHighlightFields()).contains(TITLE_FIELD));
        assertEquals(List.of(filters.get(0), "{!bbox sfield=" + SearchFields.GEOLOCATION + "}", permissionJoin()),
                Arrays.asList(query.getFilterQueries()));
        assertEquals(SearchFields.DISCOVERABLE_BY + ":" + IndexServiceBean.getPublicGroupString(), query.get("q1"));
        assertEquals("35,15", query.get("pt"));
        assertEquals("5", query.get("d"));
        assertEquals(5, query.getStart().intValue());
        assertEquals(10, query.getRows().intValue());
        assertEquals(Map.of("ocen", List.of("ocean")), result.getSpellingSuggestionsByToken());
        assertEquals(expected, result.getSolrSearchResults().get(0).getQuery());
        assertEquals(List.of(TITLE_FIELD), result.getSolrSearchResults().get(0).getMatchedFields());
        assertMappedDataset(result);
        verifyNoInteractions(service.queryEmbeddingService, service.dvObjectService);
    }

    private SolrQueryResponse semantic(int start, int rows, boolean retrieveEntities) throws SearchException {
        return service.search(request, List.of(), QUERY, List.of(), SearchFields.NAME_SORT, "asc",
                start, false, rows, retrieveEntities, null, null, true, true, RetrievalMode.SEMANTIC);
    }

    private SolrQuery capturedQuery() throws Exception {
        ArgumentCaptor<SolrQuery> query = ArgumentCaptor.forClass(SolrQuery.class);
        verify(client).query(query.capture());
        return query.getValue();
    }

    private static QueryEmbeddingService.Result embedding(int topK) {
        return new QueryEmbeddingService.Result(List.of(1f, -2.5f, 0.25f), MODEL, PIPELINE, topK);
    }

    private static FacetField facet(String field, String value, long count) {
        FacetField facet = new FacetField(field);
        facet.add(value, count);
        return facet;
    }

    private static String permissionJoin() {
        return "{!join from=" + SearchFields.DEFINITION_POINT + " to=id v=$q1}";
    }

    private static void assertFacet(FacetCategory facet, String field, String value, long count) {
        assertEquals(field, facet.getName());
        assertEquals(1, facet.getFacetLabel().size());
        FacetLabel label = facet.getFacetLabel().get(0);
        assertEquals(count, label.getCount().longValue());
        assertEquals(field + ":\"" + value + "\"", label.getFilterQuery());
    }

    private static void assertMappedDataset(SolrQueryResponse result) {
        assertEquals(1, result.getSolrSearchResults().size());
        SolrSearchResult dataset = result.getSolrSearchResults().get(0);
        assertEquals(DOCUMENT_ID, dataset.getId());
        assertEquals(DATASET_ID, dataset.getEntityId().longValue());
        assertEquals(VERSION_ID, dataset.getDatasetVersionId());
        assertEquals(SearchConstants.DATASETS, dataset.getType());
        assertEquals(0.875f, dataset.getScore());
        assertEquals("Ocean observations", dataset.getTitle());
        assertEquals("First description. Second description.", dataset.getDescriptionNoSnippet());
        assertTrue(dataset.isPublishedState());
        assertEquals("https://dataverse.invalid/dataset.xhtml?globalId=doi:10.5072/OCEAN", dataset.getHtmlUrl());
    }
}

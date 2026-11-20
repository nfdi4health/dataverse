package edu.harvard.iq.dataverse.api;

import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.DataverseServiceBean;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.authorization.users.GuestUser;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.search.FacetCategory;
import edu.harvard.iq.dataverse.search.FacetLabel;
import edu.harvard.iq.dataverse.search.SearchConstants;
import edu.harvard.iq.dataverse.search.SearchException;
import edu.harvard.iq.dataverse.search.SearchFields;
import edu.harvard.iq.dataverse.search.SearchServiceBean;
import edu.harvard.iq.dataverse.search.SearchServiceBean.RetrievalMode;
import edu.harvard.iq.dataverse.search.SolrQueryResponse;
import edu.harvard.iq.dataverse.search.SolrSearchResult;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.util.testing.JvmSetting;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import jakarta.ejb.EJBException;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.solr.client.solrj.SolrQuery;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@LocalJvmSettings
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@JvmSetting(key = JvmSettings.FEATURE_FLAG, varArgs = "semantic-search", value = "true")
@JvmSetting(key = JvmSettings.SEARCH_SEMANTIC_TOP_K, value = "25")
class SearchTest {

    private static final String QUERY = "  How do ocean:currents AND \"sea temperatures\" relate? {!term f=title} 😀  ";
    private static final String SENSITIVE = "secret-provider-token http://embedding.internal vector=[0.125,-0.75]";
    private static final String API_URL = "https://dataverse.example/api/datasets/42";

    private Search api;
    private ContainerRequestContext context;
    private HttpServletResponse httpResponse;
    private AuthenticatedUser user;
    private Dataverse root;
    private SolrQueryResponse results;
    private String query;
    private List<String> types;
    private String sort;
    private String order;
    private int perPage;
    private int start;
    private boolean showRelevance;
    private boolean showFacets;
    private boolean showEntityIds;
    private boolean showApiUrls;
    private boolean showMyData;
    private boolean showTypeCounts;
    private List<String> metadataFields;

    @BeforeEach
    void setUp() throws Exception {
        api = new Search();
        api.searchService = mock(SearchServiceBean.class);
        api.dataverseService = mock(DataverseServiceBean.class);
        api.settingsSvc = mock(SettingsServiceBean.class);
        api.httpRequest = mock(HttpServletRequest.class);
        when(api.httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(api.httpRequest.getHeaders(anyString())).thenAnswer(invocation -> Collections.emptyEnumeration());
        context = mock(ContainerRequestContext.class);
        httpResponse = mock(HttpServletResponse.class);
        user = mock(AuthenticatedUser.class);
        when(user.isAuthenticated()).thenReturn(true);
        when(context.getProperty(ApiConstants.CONTAINER_REQUEST_CONTEXT_USER)).thenReturn(user);
        root = mock(Dataverse.class);
        when(api.dataverseService.findRootDataverse()).thenReturn(root);

        results = new SolrQueryResponse(new SolrQuery("internal query"));
        results.setSolrSearchResults(List.of(datasetResult()));
        results.setNumResultsFound(17L);
        results.setResultsStart(0L);
        results.setSpellingSuggestionsByToken(Map.of("oceen", List.of("ocean")));
        results.setFacetCategoryList(List.of(facet("subject_ss", "Subject", "Earth Sciences", 17L)));
        results.setTypeFacetCategories(List.of(facet(SearchFields.TYPE, "Type", SearchConstants.UI_DATASETS, 17L)));
        serviceCall().thenReturn(results);

        query = QUERY;
        types = List.of(SearchConstants.DATASET);
        sort = null;
        order = null;
        perPage = 0;
        start = 0;
        showRelevance = false;
        showFacets = false;
        showEntityIds = false;
        showApiUrls = false;
        showMyData = false;
        showTypeCounts = false;
        metadataFields = List.of();
    }

    @ParameterizedTest
    @CsvSource({",", "score,", "score,desc", ",desc"})
    void acceptsOnlyScoreDescendingSortAndForwardsQueryUnchanged(String field, String direction) throws Exception {
        sort = field;
        order = direction;
        JsonObject data = data(search("semantic"));

        assertEquals(QUERY, data.getString("q"));
        assertEquals("semantic", data.getString("search_mode"));
        assertEquals(25, data.getInt("top_k"));
        assertEquals("top_k", data.getString("result_scope"));
        assertEquals(17, data.getInt("total_count"));
        assertEquals(0, data.getInt("start"));
        assertEquals(1, data.getInt("count_in_response"));
        assertTrue(data.getJsonObject("spelling_alternatives").isEmpty());
        assertFalse(data.containsKey("facets"));
        assertFalse(data.containsKey("total_count_per_object_type"));
        verifyService(RetrievalMode.SEMANTIC, 10, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"semantic", "unknown"})
    void authenticationFailurePrecedesSemanticAndModeValidation(String mode) {
        when(context.getProperty(ApiConstants.CONTAINER_REQUEST_CONTEXT_USER)).thenReturn(GuestUser.get());
        when(api.settingsSvc.isFalseForKey(SettingsServiceBean.Key.SearchApiRequiresToken, false)).thenReturn(false);
        query = " ";
        types = List.of();

        Response response = search(mode);

        assertEquals(401, response.getStatus());
        assertEquals(Json.createObjectBuilder().add("status", "ERROR")
                .add("message", AbstractApiBean.RESPONSE_MESSAGE_AUTHENTICATED_USER_REQUIRED).build(), response.getEntity());
        verify(api.settingsSvc).isFalseForKey(SettingsServiceBean.Key.SearchApiRequiresToken, false);
        verifyNoInteractions(api.searchService, api.dataverseService);
    }

    @Test
    void missingSubtreeReturnsBadRequestBeforeServiceCall() {
        assertBadRequest(search("semantic", List.of("missing-subtree"), List.of(), null, null),
                "Could not find dataverse with alias missing-subtree");
        verify(api.dataverseService).findByAlias("missing-subtree");
    }

    @ParameterizedTest
    @CsvSource({"'42.3,-71.1',,geo_radius", ",5,geo_point"})
    void geographicParametersMustBePairedBeforeServiceCall(String point, String radius, String missingParameter) {
        assertBadRequest(search("semantic", List.of(), List.of(), point, radius), "must also supply " + missingParameter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"semantic", "keyword"})
    void forwardsNonrootSubtreeUserFilterAndGeographyWithRequestUser(String mode) throws Exception {
        Dataverse subtree = mock(Dataverse.class);
        when(api.dataverseService.findByAlias("ocean")).thenReturn(subtree);
        when(api.dataverseService.determineDataversePath(subtree)).thenReturn("/research/ocean");
        String filter = "subject_ss:\"Earth Sciences\"";

        data(search(mode, List.of("ocean"), List.of(filter), "42,-71", "5"));

        ArgumentCaptor<DataverseRequest> request = ArgumentCaptor.forClass(DataverseRequest.class);
        verify(api.searchService).search(request.capture(), eq(List.of(subtree)), eq(QUERY),
                argThat(filters -> filters.size() == 3 && filters.contains(filter)
                        && filters.contains(SearchFields.TYPE + ":(" + SearchConstants.DATASETS + ")")
                        && filters.stream().anyMatch(fq -> fq.startsWith(SearchFields.SUBTREE + ":(")
                                && fq.contains("\"/research/ocean\""))),
                eq(SearchFields.RELEVANCE), eq("desc"), eq(0), eq(false), eq(10), eq(true), eq("42.0,-71.0"), eq("5"),
                eq(false), eq(false), eq("semantic".equals(mode) ? RetrievalMode.SEMANTIC : RetrievalMode.KEYWORD));
        assertSame(user, request.getValue().getUser());
        verify(api.dataverseService).determineDataversePath(subtree);
        verifyNoMoreInteractions(api.searchService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "SEMANTIC", "hybrid", "unknown", " semantic "})
    void rejectsUnknownModes(String mode) {
        assertBadRequest(search(mode), "search_mode");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingOrBlankSemanticQuery(String invalidQuery) {
        query = invalidQuery;
        assertBadRequest(search("semantic"), "nonblank q");
    }

    @ParameterizedTest
    @MethodSource("invalidTypes")
    void requiresExplicitDatasetOnly(List<String> invalidTypes) {
        types = invalidTypes;
        assertBadRequest(search("semantic"), "type=dataset only");
    }

    static Stream<Arguments> invalidTypes() {
        return Stream.of(List.<String>of(), List.of("file"), List.of("dataverse"),
                List.of("dataset", "file"), List.of("dataset", "dataverse"), List.of("datasets"), List.of("unknown"))
                .map(Arguments::of);
    }

    @ParameterizedTest
    @CsvSource({"name,", "date,desc", "score,asc", ",asc", "relevance,desc", "unknown,desc", "score,invalid"})
    void rejectsNonScoreDescendingSorts(String field, String direction) {
        sort = field;
        order = direction;
        assertBadRequest(search("semantic"), direction != null && direction.equals("invalid") ? "order" : "sort");
    }

    @Test
    void rejectsMyData() {
        showMyData = true;
        assertBadRequest(search("semantic"), "show_my_data");
    }

    @ParameterizedTest
    @CsvSource({"-1,10,start", "0,-1,less than zero", "0,1001,max limit", "0,26,candidate window",
            "16,0,candidate window", "24,2,candidate window", "2147483647,1,candidate window"})
    void validatesPagingBeforeCallingEjb(int requestedStart, int requestedRows, String message) throws Exception {
        start = requestedStart;
        perPage = requestedRows;
        // A container-wrapped unchecked failure must not turn an invalid page into a 503.
        serviceCall().thenThrow(new EJBException(new IllegalArgumentException(SENSITIVE)));
        assertBadRequest(search("semantic"), message);
    }

    @ParameterizedTest
    @CsvSource({"0,0,10", "15,0,10", "24,1,1", "0,25,25"})
    void acceptsPagesWithinCandidateWindowIncludingDefaultTen(int requestedStart, int requestedRows, int effectiveRows)
            throws Exception {
        start = requestedStart;
        perPage = requestedRows;
        results.setResultsStart((long) start);
        assertEquals(start, data(search("semantic")).getInt("start"));
        verifyService(RetrievalMode.SEMANTIC, effectiveRows, false);
    }

    @Test
    @JvmSetting(key = JvmSettings.FEATURE_FLAG, varArgs = "semantic-search", value = "false")
    void disabledSemanticSearchIsSanitizedWithoutCallingService() {
        assertUnavailable(search("semantic"));
        verifyNoInteractions(api.searchService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-1", "not-an-integer", "2147483648", "25.0"})
    @JvmSetting(key = JvmSettings.SEARCH_SEMANTIC_TOP_K, value = "25")
    void invalidTopKIsSanitizedWithoutCallingService(String topK) {
        String key = JvmSettings.SEARCH_SEMANTIC_TOP_K.getScopedKey();
        if (topK == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, topK);
        }
        assertUnavailable(search("semantic"));
        verifyNoInteractions(api.searchService);
    }

    @ParameterizedTest
    @MethodSource("serviceFailures")
    void sanitizesServiceExceptions(Throwable failure) throws Exception {
        serviceCall().thenThrow(failure);
        assertUnavailable(search("semantic"));
        verifyService(RetrievalMode.SEMANTIC, 10, false);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"keyword"})
    void keywordSearchExceptionRetainsDetailsWhileSemanticFailureIsSanitized(String mode) throws Exception {
        start = 3;
        serviceCall().thenThrow(new SearchException("Backend search failed", new IllegalStateException(SENSITIVE)));

        Response response = search(mode);

        assertEquals(500, response.getStatus());
        JsonObject body = (JsonObject) response.getEntity();
        assertEquals("ERROR", body.getString("status"));
        String message = body.getString("message");
        assertTrue(message.contains(QUERY), message);
        assertTrue(message.contains(SearchFields.TYPE + ":(" + SearchConstants.DATASETS + ")"), message);
        assertTrue(message.contains("paginationStart [3]"), message);
        assertTrue(message.contains("Backend search failed"), message);
        assertTrue(message.contains(SENSITIVE), message);
        verifyService(RetrievalMode.KEYWORD, 10, false);

        assertUnavailable(search("semantic"));
        verifyService(RetrievalMode.SEMANTIC, 10, false);
    }

    static Stream<Arguments> serviceFailures() {
        return Stream.of(
                new SearchException(SENSITIVE, new IllegalStateException("nested " + SENSITIVE)),
                new IllegalStateException(SENSITIVE),
                new EJBException(SENSITIVE, new IllegalArgumentException(SENSITIVE)))
                .map(Arguments::of);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sanitizesReturnedErrorsAndTemporaryUnavailability(boolean unavailable) throws Exception {
        if (unavailable) {
            results.setSolrTemporarilyUnavailable(true);
        } else {
            results.setError(SENSITIVE);
        }
        assertUnavailable(search("semantic"));
        verifyService(RetrievalMode.SEMANTIC, 10, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesResponseOptionsAndRealItemMappingWithoutEmbeddingInternals(boolean options) throws Exception {
        showRelevance = options;
        showEntityIds = options;
        showApiUrls = options;
        showFacets = options;
        showTypeCounts = options;
        results.getSolrQuery().setQuery("{!knn f=" + SearchFields.DATASET_EMBEDDING + " topK=25}[0.125,-0.75]");
        results.getSolrQuery().set("semanticModel", SENSITIVE);
        results.getSolrQuery().set("semanticPipeline", "private-pipeline");
        results.getSolrQuery().setFilterQueries(SearchFields.EMBEDDER + ":private-model",
                SearchFields.EMBEDDING_PIPELINE + ":private-provenance");
        results.setFilterQueriesActual(List.of(SENSITIVE));

        JsonObject data = data(search("semantic"));
        JsonObject item = data.getJsonArray("items").getJsonObject(0);
        assertEquals("Ocean observations", item.getString("name"));
        assertEquals("dataset", item.getString("type"));
        assertEquals("doi:10.5072/OCEAN", item.getString("global_id"));
        assertEquals("https://doi.org/10.5072/OCEAN", item.getString("url"));
        assertEquals("Ocean temperature measurements", item.getString("description"));
        assertEquals("Research data", item.getString("publisher"));
        assertEquals(options, item.containsKey("entity_id"));
        assertEquals(options, item.containsKey("api_url"));
        assertEquals(options, item.containsKey("score"));
        assertEquals(options, item.containsKey("matches"));
        assertEquals(options, data.containsKey("facets"));
        assertEquals(options, data.containsKey("total_count_per_object_type"));
        if (options) {
            assertEquals(42, item.getInt("entity_id"));
            assertEquals(API_URL, item.getString("api_url"));
            assertEquals(0.875, item.getJsonNumber("score").doubleValue(), 0.000001);
            assertTrue(item.getJsonArray("matches").isEmpty());
            JsonObject facet = data.getJsonArray("facets").getJsonObject(0).getJsonObject("subject_ss");
            assertEquals("Subject", facet.getString("friendly"));
            assertEquals(17, facet.getJsonArray("labels").getJsonObject(0).getInt("Earth Sciences"));
            assertEquals(Json.createObjectBuilder().add(SearchConstants.UI_DATASETS, 17)
                    .add(SearchConstants.UI_DATAVERSES, 0).add(SearchConstants.UI_FILES, 0).build(),
                    data.getJsonObject("total_count_per_object_type"));
        }
        Set<String> expectedKeys = options
                ? Set.of("name", "type", "url", "global_id", "description", "publisher", "score", "matches", "entity_id", "api_url")
                : Set.of("name", "type", "url", "global_id", "description", "publisher");
        assertEquals(expectedKeys, item.keySet());
        for (String internal : List.of(SENSITIVE, "knn", "0.125", "-0.75", "private-pipeline", "private-model",
                "private-provenance", SearchFields.DATASET_EMBEDDING, SearchFields.EMBEDDER, SearchFields.EMBEDDING_PIPELINE)) {
            assertFalse(data.toString().contains(internal), internal);
        }
        assertTrue(data.getJsonObject("spelling_alternatives").isEmpty());
        verifyService(RetrievalMode.SEMANTIC, 10, false);
    }

    @Test
    void forwardsMetadataFieldsAndRelevanceToItemSerializerWithoutHighlights() throws Exception {
        metadataFields = List.of("citation:title", "citation:author");
        showRelevance = true;
        showEntityIds = true;
        showApiUrls = true;
        SolrSearchResult item = mock(SolrSearchResult.class);
        when(item.json(true, true, true, metadataFields)).thenReturn(Json.createObjectBuilder().add("metadata", "requested"));
        results.setSolrSearchResults(List.of(item));

        assertEquals("requested", data(search("semantic")).getJsonArray("items").getJsonObject(0).getString("metadata"));
        verify(item).json(eq(true), eq(true), eq(true), same(metadataFields));
        verifyService(RetrievalMode.SEMANTIC, 10, false);
    }

    @Test
    void emptySemanticResultsKeepEmptySpellingFacetsAndZeroTypeCounts() {
        results.setSolrSearchResults(List.of());
        results.setNumResultsFound(0L);
        results.setFacetCategoryList(List.of());
        results.setTypeFacetCategories(List.of());
        showFacets = true;
        showTypeCounts = true;

        JsonObject data = data(search("semantic"));
        assertEquals(0, data.getInt("total_count"));
        assertEquals(0, data.getInt("count_in_response"));
        assertTrue(data.getJsonArray("items").isEmpty());
        assertTrue(data.getJsonObject("spelling_alternatives").isEmpty());
        assertEquals(Json.createArrayBuilder().add(Json.createObjectBuilder()).build(), data.getJsonArray("facets"));
        assertEquals(Json.createObjectBuilder().add(SearchConstants.UI_DATASETS, 0)
                .add(SearchConstants.UI_DATAVERSES, 0).add(SearchConstants.UI_FILES, 0).build(),
                data.getJsonObject("total_count_per_object_type"));
    }

    @Test
    @JvmSetting(key = JvmSettings.FEATURE_FLAG, varArgs = "semantic-search", value = "false")
    @JvmSetting(key = JvmSettings.SEARCH_SEMANTIC_TOP_K, value = "invalid")
    void defaultAndExplicitKeywordPreserveResponseWithoutReadingSemanticSettings() throws Exception {
        showRelevance = true;
        showEntityIds = true;
        showApiUrls = true;
        showFacets = true;
        showTypeCounts = true;
        // Any settings lookup here is a regression: keyword mode does not need semantic configuration.
        try (MockedStatic<ConfigProvider> config = mockStatic(ConfigProvider.class)) {
            config.when(ConfigProvider::getConfig).thenThrow(new AssertionError("Unexpected semantic settings lookup"));
            JsonObject implicit = data(search(null));
            JsonObject explicit = data(search("keyword"));
            assertEquals(implicit, explicit);
            assertEquals("[ocean]", implicit.getJsonObject("spelling_alternatives").getString("oceen"));
            assertFalse(implicit.containsKey("search_mode"));
            assertFalse(implicit.containsKey("top_k"));
            assertFalse(implicit.containsKey("result_scope"));
            assertEquals(0.875, implicit.getJsonArray("items").getJsonObject(0).getJsonNumber("score").doubleValue(), 0.000001);
            config.verifyNoInteractions();
        }
        verify(api.searchService, times(2)).search(any(DataverseRequest.class), eq(List.of(root)), eq(QUERY), anyList(),
                eq(SearchFields.RELEVANCE), eq("desc"), eq(0), eq(false), eq(10), eq(true), isNull(), isNull(),
                eq(true), eq(true), eq(RetrievalMode.KEYWORD));
    }

    private Response search(String mode) {
        return search(mode, List.of(), List.of(), null, null);
    }

    private Response search(String mode, List<String> subtrees, List<String> filters, String point, String radius) {
        return api.search(context, query, types, subtrees, sort, order, perPage, start, showRelevance, showFacets,
                new ArrayList<>(filters), showEntityIds, showApiUrls, showMyData, false, metadataFields, point, radius,
                showTypeCounts, mode, httpResponse);
    }

    private org.mockito.stubbing.OngoingStubbing<SolrQueryResponse> serviceCall() throws SearchException {
        return when(api.searchService.search(any(DataverseRequest.class), anyList(), anyString(), anyList(), anyString(),
                anyString(), anyInt(), anyBoolean(), anyInt(), anyBoolean(), nullable(String.class), nullable(String.class),
                anyBoolean(), anyBoolean(), any(RetrievalMode.class)));
    }

    private void verifyService(RetrievalMode mode, int rows, boolean highlights) throws SearchException {
        ArgumentCaptor<DataverseRequest> request = ArgumentCaptor.forClass(DataverseRequest.class);
        verify(api.searchService).search(request.capture(), eq(List.of(root)), eq(query),
                eq(List.of(SearchFields.TYPE + ":(" + SearchConstants.DATASETS + ")", "")),
                eq(SearchFields.RELEVANCE), eq("desc"), eq(start), eq(false), eq(rows), eq(true), isNull(), isNull(),
                eq(showFacets), eq(highlights), eq(mode));
        assertSame(user, request.getValue().getUser());
        verifyNoMoreInteractions(api.searchService);
    }

    private void assertBadRequest(Response response, String message) {
        assertEquals(400, response.getStatus());
        JsonObject body = (JsonObject) response.getEntity();
        assertEquals("ERROR", body.getString("status"));
        assertTrue(body.getString("message").contains(message), body.toString());
        verifyNoInteractions(api.searchService);
    }

    private void assertUnavailable(Response response) {
        assertEquals(503, response.getStatus());
        assertEquals(Json.createObjectBuilder().add("status", "ERROR")
                .add("message", "Semantic search is temporarily unavailable.").build(), response.getEntity());
    }

    private JsonObject data(Response response) {
        assertEquals(200, response.getStatus(), () -> response.getEntity().toString());
        JsonObject body = (JsonObject) response.getEntity();
        assertEquals("OK", body.getString("status"));
        return body.getJsonObject("data");
    }

    private SolrSearchResult datasetResult() throws Exception {
        // The constructor is package-private; retain the real serializer rather than mocking its explicit field mapping.
        var constructor = SolrSearchResult.class.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        SolrSearchResult item = constructor.newInstance(QUERY, "Ocean observations");
        item.setType(SearchConstants.DATASETS);
        item.setTitle("Ocean observations");
        item.setIdentifier("doi:10.5072/OCEAN");
        item.setPersistentUrl("https://doi.org/10.5072/OCEAN");
        item.setDescriptionNoSnippet("Ocean temperature measurements");
        item.setParent(Map.of("name", "Research data"));
        item.setEntityId(42L);
        item.setApiUrl(API_URL);
        item.setScore(0.875f);
        item.setHighlightsMap(Map.of());
        return item;
    }

    private FacetCategory facet(String name, String friendlyName, String label, long count) {
        FacetCategory facet = new FacetCategory();
        facet.setName(name);
        facet.setFriendlyName(friendlyName);
        facet.setFacetLabel(List.of(new FacetLabel(label, count)));
        return facet;
    }
}

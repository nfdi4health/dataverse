package edu.harvard.iq.dataverse.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Uses Meilisearch to rank dataset candidates and Solr to apply permissions,
 * filters and the response shape expected by Dataverse callers.
 */
@Stateless
@Named
public class MeilisearchSearchServiceBean implements SearchService {

    static final String SERVICE_NAME = "meilisearch";
    static final String DATASET_FILTER = SearchFields.TYPE + " = \"" + SearchConstants.DATASETS + "\"";

    private static final Pattern BOOLEAN_OPERATOR = Pattern.compile("(^|\\s)(AND|OR|NOT)(\\s|$)");
    private static final Pattern UNSUPPORTED_SYNTAX = Pattern.compile("[:()\\[\\]{}~^?*\\\\&|!+/]");
    private static final int SOLR_DOCUMENTS_PER_DATASET = 2;
    private static final String NO_RESULTS_QUERY = SearchFields.ID + ":\"__meilisearch_no_results__\"";

    @Inject
    MeilisearchClient meilisearchClient;

    @Inject
    MeilisearchConfiguration configuration;

    private SearchService solrSearchService;

    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    public String getDisplayName() {
        return "Meilisearch";
    }

    @Override
    public void setSolrSearchService(SearchService solrSearchService) {
        this.solrSearchService = solrSearchService;
    }

    @Override
    public SolrQueryResponse search(DataverseRequest dataverseRequest, List<Dataverse> dataverses, String query,
            List<String> filterQueries, String sortField, String sortOrder, int paginationStart,
            boolean onlyDataRelatedToMe, int numResultsPerPage, boolean retrieveEntities, String geoPoint,
            String geoRadius, boolean addFacets, boolean addHighlights, boolean addCollections) throws SearchException {
        validateRequest(paginationStart, numResultsPerPage);
        List<String> effectiveFilters = filterQueries == null ? List.of() : filterQueries;

        if (!supportsQuery(query) || onlyDataRelatedToMe || hasText(geoPoint) || hasText(geoRadius)
                || requiresNonDatasetResults(effectiveFilters)) {
            return searchSolr(dataverseRequest, dataverses, query, effectiveFilters, sortField, sortOrder,
                    paginationStart, onlyDataRelatedToMe, numResultsPerPage, retrieveEntities, geoPoint, geoRadius,
                    addFacets, addHighlights, addCollections);
        }

        LinkedHashMap<String, Float> rankingByPid = queryMeilisearch(query);
        if (rankingByPid.isEmpty()) {
            SolrQueryResponse response = searchSolr(dataverseRequest, dataverses, NO_RESULTS_QUERY,
                    effectiveFilters, null, sortOrder, 0, false, 1, retrieveEntities, null, null,
                    addFacets, false, addCollections);
            response.setResultsStart((long) paginationStart);
            response.setSpellingSuggestionsByToken(Map.of());
            return response;
        }

        boolean useMeilisearchRanking = sortField == null || SearchFields.RELEVANCE.equals(sortField);
        String candidateQuery = buildCandidateQuery(new ArrayList<>(rankingByPid.keySet()));
        int hydrationLimit = rankingByPid.size() * SOLR_DOCUMENTS_PER_DATASET;
        SolrQueryResponse response = searchSolr(dataverseRequest, dataverses, candidateQuery, effectiveFilters,
                useMeilisearchRanking ? null : sortField, sortOrder, 0, false, hydrationLimit,
                retrieveEntities, null, null, addFacets, false, addCollections);

        List<SolrSearchResult> accessibleResults = new ArrayList<>(response.getSolrSearchResults());
        Map<String, Integer> rankByPid = new LinkedHashMap<>();
        int rank = 0;
        for (String pid : rankingByPid.keySet()) {
            rankByPid.put(pid, rank++);
        }
        for (SolrSearchResult result : accessibleResults) {
            Float score = rankingByPid.get(result.getIdentifier());
            if (score != null) {
                result.setScore(score);
            }
            result.setHighlightsAsList(List.of());
            result.setHighlightsMap(Map.of());
            result.setHighlightsAsMap(Map.of());
        }
        if (useMeilisearchRanking) {
            Comparator<SolrSearchResult> ranking = Comparator.comparingInt(
                    result -> rankByPid.getOrDefault(result.getIdentifier(), Integer.MAX_VALUE));
            if (SortBy.ASCENDING.equals(sortOrder)) {
                ranking = ranking.reversed();
            }
            accessibleResults.sort(ranking);
        }

        int end = Math.min(paginationStart + numResultsPerPage, accessibleResults.size());
        List<SolrSearchResult> page = paginationStart >= accessibleResults.size()
                ? List.of()
                : new ArrayList<>(accessibleResults.subList(paginationStart, end));
        response.setSolrSearchResults(page);
        response.setNumResultsFound((long) accessibleResults.size());
        response.setResultsStart((long) paginationStart);
        response.setSpellingSuggestionsByToken(Map.of());
        return response;
    }

    protected LinkedHashMap<String, Float> queryMeilisearch(String query) throws SearchException {
        String effectiveQuery = query == null || query.isBlank() || "*".equals(query.trim()) ? "" : query;
        String embedder = configuration.embedder().orElse(null);
        Double semanticRatio;
        try {
            semanticRatio = embedder == null ? null : configuration.semanticRatio().orElse(0.5);
        } catch (IllegalArgumentException ex) {
            throw new SearchException(ex.getMessage(), ex);
        }
        int candidateLimit;
        try {
            candidateLimit = configuration.candidateLimit();
        } catch (IllegalArgumentException ex) {
            throw new SearchException(ex.getMessage(), ex);
        }

        MeilisearchClient.SearchResult result = meilisearchClient.search(new MeilisearchClient.SearchRequest(
                effectiveQuery, candidateLimit, DATASET_FILTER, embedder, semanticRatio));
        LinkedHashMap<String, Float> rankingByPid = new LinkedHashMap<>();
        for (MeilisearchClient.SearchHit hit : result.hits()) {
            rankingByPid.putIfAbsent(hit.persistentId(), hit.rankingScore());
        }
        return rankingByPid;
    }

    static boolean supportsQuery(String query) {
        if (query == null || query.isBlank() || "*".equals(query.trim())) {
            return true;
        }
        StringBuilder unquoted = new StringBuilder(query.length());
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < query.length(); index++) {
            char current = query.charAt(index);
            if (escaped) {
                escaped = false;
                unquoted.append(quoted ? ' ' : current);
            } else if (current == '\\') {
                escaped = true;
                unquoted.append(quoted ? ' ' : current);
            } else if (current == '"') {
                quoted = !quoted;
                unquoted.append(' ');
            } else {
                unquoted.append(quoted ? ' ' : current);
            }
        }
        if (quoted || escaped) {
            return false;
        }
        String syntax = unquoted.toString();
        return !UNSUPPORTED_SYNTAX.matcher(syntax).find() && !BOOLEAN_OPERATOR.matcher(syntax).find();
    }

    static boolean requiresNonDatasetResults(List<String> filterQueries) {
        for (String filter : filterQueries) {
            String normalized = filter.replaceAll("\\s", "").replace("\"", "");
            if (normalized.startsWith(SearchFields.TYPE + ":")) {
                String value = normalized.substring((SearchFields.TYPE + ":").length());
                if (value.startsWith("(") && value.endsWith(")")) {
                    value = value.substring(1, value.length() - 1);
                }
                return !SearchConstants.DATASETS.equals(value);
            }
        }
        return false;
    }

    static String buildCandidateQuery(List<String> pids) {
        return SearchFields.DATASET_PERSISTENT_ID + ":("
                + String.join(" OR ", pids.stream().map(MeilisearchSearchServiceBean::quote).toList()) + ")";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private SolrQueryResponse searchSolr(DataverseRequest request, List<Dataverse> dataverses, String query,
            List<String> filters, String sortField, String sortOrder, int start, boolean onlyMine, int rows,
            boolean retrieveEntities, String geoPoint, String geoRadius, boolean facets, boolean highlights,
            boolean collections) throws SearchException {
        if (solrSearchService == null) {
            throw new SearchException("Solr search service is not configured", null);
        }
        return solrSearchService.search(request, dataverses, query, filters, sortField, sortOrder, start, onlyMine,
                rows, retrieveEntities, geoPoint, geoRadius, facets, highlights, collections);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void validateRequest(int start, int rows) {
        if (start < 0) {
            throw new IllegalArgumentException("paginationStart must be 0 or greater");
        }
        if (rows < 1) {
            throw new IllegalArgumentException("numResultsPerPage must be 1 or greater");
        }
    }
}

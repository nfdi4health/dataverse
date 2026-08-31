Search Services
===============

Dataverse supports configurable search services, allowing developers to integrate additional search engines dynamically. This guide outlines the design and provides details on how to use the interfaces and classes involved.

Design Overview
---------------
The configurable search services feature is designed to allow:

1. Dynamic addition of new search engines
2. Configuration of the Dataverse UI to use a specified search engine
3. Use of different search engines via the API
4. Discovery of installed search engines

Key Components
--------------

1. SearchService Interface
^^^^^^^^^^^^^^^^^^^^^^^^^^
The ``SearchService`` interface is the core of the configurable search services. It defines the methods that any search engine implementation must provide. (The methods below are accurate as of this writing.)

.. code-block:: java

   public interface SearchService {
       String getServiceName();
       String getDisplayName();
       
       SolrQueryResponse search(DataverseRequest dataverseRequest, List<Dataverse> dataverses, String query,
               List<String> filterQueries, String sortField, String sortOrder, int paginationStart,
               boolean onlyDatatRelatedToMe, int numResultsPerPage, boolean retrieveEntities, String geoPoint,
               String geoRadius, boolean addFacets, boolean addHighlights) throws SearchException;

       default void setSolrSearchService(SearchService solrSearchService);
   }

The interface allows you to provide a service name and display name, and to respond to the same search parameters that are normally sent to the Solr search engine.

The ``setSolrSearchService`` method is used by Dataverse to give your class a reference to the ``SolrSearchService``, allowing your class to perform Solr queries as needed. (See the ``ExternalSearchServices`` for an example.)

2. ConfigurableSearchService Interface
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The ``ConfigurableSearchService`` interface extends the ``SearchService`` interface and adds a method for Dataverse to set the ``SettingsServiceBean``. This allows search services to be configurable through Dataverse settings.

.. code-block:: java

   public interface ConfigurableSearchService extends SearchService {
       void setSettingsService(SettingsServiceBean settingsService);
   }

The ``GetExternalSearchServiceBean`` and ``PostExternalSearchServiceBean`` classes provide a use case for this.

3. JVM Options for Search Configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
Dataverse uses two JVM options to configure the search functionality:

- ``dataverse.search.services.directory``: Specifies the local directory where jar files with search engines (classes implementing the ``SearchService`` interface) can be found. Dataverse will dynamically load engines from this directory.

- ``dataverse.search.default-service``: The ``serviceName`` of the service that should be used in the Dataverse UI.

Example configuration:

.. code-block:: bash

   ./asadmin create-jvm-options "-Ddataverse.search.services.directory=/var/lib/dataverse/searchServices"
   ./asadmin create-jvm-options "-Ddataverse.search.default-service=solr"

Remember to restart your Payara server after modifying these JVM options for the changes to take effect.

4. Using Different Search Engines via API
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The loaded search services can be discovered using the ``/api/search/services`` endpoint.

Queries can be made to different engines by including the optional ``search_service=<serviceName>`` query parameter.

Use of these endpoints is described for end users in the API Guide under :ref:`search-services`.

Available Search Services
-------------------------

The class definitions for four example search services are included in the Dataverse repository.
They are not included in the Dataverse .war file but can be built as three separate .jar files using

.. code-block:: bash 

    mvn clean package -DskipTests=true -Pexternal-search-get -Pexternal-search-post

or

.. code-block:: bash 

    mvn clean package -DskipTests=true -Ptrivial-search-examples

1. GetExternalSearchServiceBean
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

2. PostExternalSearchServiceBean
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

These classes implement the ``ConfigurableSearchService`` interface.
They make a GET or POST call (respectively) to an external search engine that must return a JSON array of objects with "PID" (preferred) or "DOI" and "Distance" keys.
The query sent to the external engine use the same query parameters as the Dataverse search API (GET) or have a JSON payload with those keys (POST).
The results they return are then searched for using the solr search engine which enforces access control and provides the standard formatting expected by the Dataverse UI and API.
The distance values are used to order the results, smallest distances first. 

They can be configured via two settings each:

- GET

  - :GetExternalSearchUrl - the URL to send GET search queries to
  - :GetExternalSearchName - the display name to use for this configuration

- POST

  - :PostExternalSearchUrl - the URL to send POST search queries to
  - :PostExternalSearchName - the display name to use for this configuration

As these classes use PIDs as identifiers, they cannot reference collections or, unless file PIDs are enabled, files.
Similar classes, or extensions of these classes could search by database ids instead, etc. to support the additional types.

3. GoldenOldiesSearchServiceBean
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

4. OddlyEnoughSearchServiceBean
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

These classes implement the ``SearchService`` interface.
They are intended only as code examples and simple tests of the design and are not intended for production use.
The former simply replaces the user query with a query for entities with a db id < 1000. It demonstrates how a class can leverage the solr engine and achieve results solely by modifying/replacing the user query. 
The latter only returns hits from the user's query that also have an odd database id. Since the filtering in the class changes the number of total hits available and pagination, this class demonstrates one way a developer can adjust those aspects of the Solr response.

5. MeilisearchSearchServiceBean
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

``MeilisearchSearchServiceBean`` is a datasets-only built-in search service. It
uses Meilisearch to rank persistent identifiers and then queries Solr for those
identifiers. Solr remains responsible for permissions, Dataverse filters,
facets, and formatting the results expected by the UI and APIs.

The service is included in the Dataverse war. Its service name is
``meilisearch``. It can be selected with ``search_service=meilisearch`` or set as
``dataverse.search.default-service``. The query adapter currently handles
datasets only, so setting it as the global default is not appropriate when
collection and file results are required.

When ``dataverse.search.meilisearch.url`` is configured, Dataverse mirrors all
non-permission Solr documents to Meilisearch. Adds, replacements, deletes, and
index clears are stored in a durable ordered queue and retried up to ten times.
Permission documents remain in Solr only. Dataset documents expose the same
``dsPersistentId`` attribute used in Solr so the adapter can hydrate and
permission-filter Meilisearch results through Solr.

If the index restricts ``displayedAttributes``, that list must include
``dsPersistentId``; otherwise Meilisearch omits the identifier from search hits
and Dataverse cannot hydrate them from Solr. An attribute with another name,
such as ``pid``, does not satisfy this contract. Dataverse uses the Solr
document identifier as the Meilisearch primary key. For example:

.. code-block:: json

    {
      "id": "dataset_123",
      "dsPersistentId": "doi:10.5072/FK2/ABC123",
      "title": "Dataset title",
      "description": "Searchable metadata"
    }

Configure the connection with ``dataverse.search.meilisearch.url`` and the
related settings described in the Installation Guide. Use a Meilisearch API key
restricted to the ``search`` action and the configured index.

The installation owns the Meilisearch index settings. To use hybrid search,
configure an embedder on the index and set
``dataverse.search.meilisearch.embedder`` to the same name. For example,
Meilisearch 1.53 can use a remote Ollama server without an OpenAI API key:

.. code-block:: json

    {
      "ollama": {
        "source": "ollama",
        "url": "https://ollama.example.org/api/embed",
        "model": "your-embedding-model",
        "documentTemplate": "{{doc.title}} {{doc.description}}"
      }
    }

Send this object to the Meilisearch
``/indexes/{index}/settings/embedders`` endpoint before adding documents. The
Ollama URL must end in ``/api/embed`` or ``/api/embeddings`` and must be
reachable from Meilisearch. If it resolves to a private address, configure
Meilisearch's ``MEILI_EXPERIMENTAL_ALLOWED_IP_NETWORKS`` setting appropriately.
Dataverse does not connect to Ollama directly and does not need the Ollama URL
or model name.

The adapter sends plain text, quoted phrases, and placeholder searches to
Meilisearch. When an embedder is configured, non-empty supported queries use
hybrid ranking with the configured semantic ratio. Lucene field queries,
ranges, boolean operators, wildcards,
geospatial searches, My Data searches, and file- or collection-only searches
fall back to Solr. Meilisearch results are collected from the start of the
ranking up to the configured candidate limit, filtered through Solr, and then
paginated. As a result, totals and facets cover accessible candidates within
that limit rather than every possible Meilisearch hit. Solr cannot recreate
Meilisearch snippets or spelling suggestions from the PID hydration query.

Notes
-----

1. Unless you use the Solr engine to provide access control, you must implement proper access control in your search engine
2. The design currently limits search results to be in the format returned by Solr and the hits are expected to be collections, datasets, or files - other classes are not supported.
3. Search services could be designed to completely replace Solr or to just support certain use cases (e.g. the external search classes only handling datasets).
4. While search services can be deployed as independent jar files, they currently import multiple Dataverse classes and, unlike exporters, cannot be built using just the Dataverse SPI.
5. As with other experimental features, we expect the ``SearchService`` interface may change over time as we learn about how people use it. Please keep in touch if you are developing search services.

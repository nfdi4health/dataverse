Solr Search Index
=================

A Dataverse installation requires Solr to be operational at all times. If you stop Solr, you should see an error about this on the root Dataverse installation page, which is powered by the search index Solr provides. You can set up Solr by following the steps in our Installation Guide's :doc:`/installation/prerequisites` and :doc:`/installation/config` sections explaining how to configure it. This section you're reading now is about the care and feeding of the search index. PostgreSQL is the "source of truth" and the Dataverse installation will copy data from PostgreSQL into Solr. For this reason, the search index can be rebuilt at any time. Depending on the amount of data you have, this can be a slow process. You are encouraged to experiment with production data to get a sense of how long a full reindexing will take.

.. contents:: Contents:
	:local:

.. _dataset-embedding-generation:

Embedding Generation and Vector Configuration
---------------------------------------------

Dataverse can generate embeddings for **published dataset documents** during ordinary indexing and reindexing. Enable this explicitly with ``dataverse.feature.semantic-embeddings=true`` in the Dataverse/Payara runtime configuration (see :ref:`feature-flags`). The flag is off unless enabled; configuring an endpoint alone does not enable generation. Draft and deaccessioned dataset documents, file documents, and Dataverse collection documents do not receive embeddings.

Document generation and query embedding preparation do **not** change keyword search, ranking, search APIs, or the user interface. The query embedding service described below prepares vectors and compatible document provenance; it does not execute retrieval. A retrieval implementation must filter by the returned ``embedder`` and compatible ``embedding_pipeline`` and retain authorization and other search filters.

The schema declares an optional ``dataset_embedding`` vector field and three optional, single-valued metadata fields: ``embedder``, ``embedding_source_hash``, and ``embedding_pipeline``. These fields have no DocValues and are not copied into the keyword-search catch-all field. Declaring them does not enable generation or semantic search.

Solr Configuration and Live Schema Validation
+++++++++++++++++++++++++++++++++++++++++++++

Before enabling embedding generation or semantic search, configure both of these JVM system properties in **Solr**, not the Dataverse/Payara JVM:

* ``solr.embedding.dimension``: **REQUIRED for semantic deployments**, the selected model's configured positive output dimension, within the deployed Solr/Lucene version's supported limits.
* ``solr.embedding.similarity``: **REQUIRED for semantic deployments**, a Solr-supported similarity function consistent with the model and the document/query normalization strategy.

Supply the properties using ``-Dsolr.embedding.dimension=...`` and ``-Dsolr.embedding.similarity=...`` in Solr's existing ``SOLR_OPTS`` configuration (for example, in ``solr.in.sh``). Replace the ellipses with the actual deployment values; they are not defaults. For the development Docker Compose environment, pass ``SOLR_OPTS`` to the ``dev_solr`` service. Preserve any existing JVM options and configure every Solr node hosting the collection consistently.

The schema defaults to one dimension and cosine similarity so keyword-only installations can load the core without embedding configuration. These startup defaults do not select an embedding model or enable semantic search. Before indexing vectors, explicitly configure dimensions and similarity for the chosen model; live schema validation rejects mismatches with Dataverse configuration. Invalid explicit Solr property values can still prevent core loading. Existing documents remain valid without embedding fields.

Before each embedding request for nonempty prepared text, Dataverse reads the **actual active Solr Schema API**, rather than assuming that the schema file or JVM options describe the loaded core. It checks that ``dataset_embedding`` is single-valued, indexed, and not stored, and resolves its field type. The type must be ``DenseVectorField`` with explicit ``vectorEncoding=FLOAT32``, a resolved positive ``vectorDimension``, and a ``similarityFunction`` exactly matching the explicitly configured Dataverse dimensions and similarity. The supplied schema explicitly declares ``FLOAT32``; apply it to the active collection before enabling generation. BYTE-encoded fields are rejected rather than silently quantizing the vector. Unresolved schema substitutions or failed schema requests prevent embedding generation. ``dot_product`` additionally requires explicit ``l2`` normalization. Embedder credentials are not forwarded to Solr.

For example, a deployment may select model ``qwen3-embedding:8b`` with ``4096`` output dimensions. These are **deployment choices, not application or schema defaults**; there is no hardcoded model/dimension mapping. For that selection, explicitly set ``dataverse.embedding.model=qwen3-embedding:8b``, ``dataverse.embedding.dimensions=4096``, and ``solr.embedding.dimension=4096``. Verify that the deployed model actually returns that dimension and that the deployed Solr/Lucene version supports indexing vectors of that size. Neither this example nor the runtime equality check establishes that the deployed Solr/Lucene supports 4096 dimensions. The actual immutable model digest is not established here and must be supplied by the deployment for Ollama.

.. _dataset-embedding-settings:

Dataverse Runtime Settings
++++++++++++++++++++++++++

Set the following keys in the **Dataverse/Payara** runtime, using JVM properties or other supported MicroProfile Config sources (see :ref:`jvm-options`). All names below begin with ``dataverse.embedding.``. There are **no deployment defaults** for these settings: the endpoint, model, dimensions, timeouts, normalization, prefix mode, and input-limit policy must be selected explicitly. **REQUIRED** means required for document generation or query embedding requests, not a suggested value. Document-processing settings are also needed for queries to derive the compatible document pipeline identifier. Conditional settings must follow their selected mode.

.. list-table:: Embedding settings
   :header-rows: 1
   :widths: 32 68

   * - Key
     - Requirement and behavior
   * - ``dataverse.embedding.protocol``
     - **REQUIRED**: ``ollama`` or ``openai-compatible``. These are the two implemented HTTP protocols; there is no provider interface or automatic protocol detection/fallback.
   * - ``dataverse.embedding.endpoint``
     - **REQUIRED**: full HTTP(S) embedding URL ending in ``/api/embed`` for Ollama or ``/v1/embeddings`` for OpenAI-compatible services. Reverse-proxy base paths are supported. The URL must have a host, no user information, query, or fragment, and a valid port if specified. Redirects are disabled.
   * - ``dataverse.embedding.model``
     - **REQUIRED**: nonblank model identifier sent to the service. The response's ``model`` must exactly match it for either protocol.
   * - ``dataverse.embedding.model-revision``
     - **REQUIRED for Ollama**: ``sha256:`` followed by exactly 64 hexadecimal characters, matching the service's model inventory digest. **Must be unset for OpenAI-compatible services**; that protocol provides no standard revision attestation.
   * - ``dataverse.embedding.auth-mode``
     - **REQUIRED**: ``none`` or ``bearer``. Authentication is never inferred from the endpoint or token.
   * - ``dataverse.embedding.auth-token``
     - **REQUIRED for bearer**: nonempty token containing only printable ASCII characters, without spaces or control characters. Supply through protected runtime configuration. **Must be unset for none**.
   * - ``dataverse.embedding.connect-timeout-ms``
     - **REQUIRED**: positive integer milliseconds for connection establishment, also used for the Solr schema connection. The embedding HTTP transport is initialized lazily and reused; changing this value after initialization requires an application restart.
   * - ``dataverse.embedding.request-timeout-ms``
     - **REQUIRED**: positive integer milliseconds for each service exchange, including receipt of the entire response body; also bounds Solr schema requests. Ollama inventory checks are separate exchanges, not covered by one shared end-to-end deadline.
   * - ``dataverse.embedding.max-response-bytes``
     - **REQUIRED**: positive Java integer bounding actual bytes received for each embedding-service response, including error bodies and Ollama inventories. Receipt is cancelled on overflow before unbounded response buffering; no response-size default is supplied.
   * - ``dataverse.embedding.dimensions``
     - **REQUIRED**: positive Java integer for the expected vector length. This validates the response and live Solr schema; it is not sent as an output-dimension override to the service.
   * - ``dataverse.embedding.similarity``
     - **REQUIRED**: ``cosine``, ``dot_product``, ``euclidean``, or ``maximum_inner_product``, exactly matching the active Solr field type. Deployment support must also be verified.
   * - ``dataverse.embedding.normalization``
     - **REQUIRED**: ``none`` or ``l2``. ``none`` performs no client-side normalization; ``l2`` normalizes the returned vector at runtime. ``dot_product`` requires ``l2``.
   * - ``dataverse.embedding.processor``
     - **REQUIRED**: ``basic-v1``, the implemented document processor. This is not an arbitrary pipeline identifier.
   * - ``dataverse.embedding.document-prefix-mode``
     - **REQUIRED**: ``none`` or ``literal``. ``none`` uses no prefix and ignores ``document-prefix``; ``literal`` requires it.
   * - ``dataverse.embedding.document-prefix``
     - **REQUIRED for literal**: nonempty literal prefix prepended exactly, without an automatically inserted separator. Include any required separator in the value. Unset this setting when using ``none`` to avoid misleading configuration.
   * - ``dataverse.embedding.max-input-code-points``
     - **REQUIRED**: positive Java integer limiting the complete prepared input, **including the prefix**, in Unicode code points, not bytes, UTF-16 code units, or model tokens. Select it for the deployed model/service; no input cap or token-limit conversion is supplied.
   * - ``dataverse.embedding.input-limit-action``
     - **REQUIRED**: ``reject`` or ``truncate``. ``reject`` fails embedding preparation if the input exceeds the limit (document indexing continues without an embedding). ``truncate`` shortens only metadata or query content and retains the entire selected prefix, without splitting surrogate pairs.

Query Embedding Configuration and Compatibility
++++++++++++++++++++++++++++++++++++++++++++++++

Enable semantic retrieval explicitly with ``dataverse.feature.semantic-search=true``. This flag is independent of ``dataverse.feature.semantic-embeddings``: searching existing vectors does not require document generation to be enabled. Both flags are off unless explicitly enabled. The query embedding service checks only the semantic-search flag.

The following settings have **no deployment defaults** and are loaded only for semantic requests. Missing or invalid query settings do not affect document indexing or keyword search.

.. list-table:: Query-only settings
   :header-rows: 1
   :widths: 38 62

   * - Key
     - Requirement and behavior
   * - ``dataverse.embedding.query-prefix-mode``
     - **REQUIRED**: ``none`` or ``literal``. ``none`` uses no query prefix and ignores ``query-prefix``.
   * - ``dataverse.embedding.query-prefix``
     - **REQUIRED for literal**: nonempty prefix applied exactly, with no implicit separator. Include any required separator in the value. Unset it for ``none`` to avoid misleading configuration.
   * - ``dataverse.search.semantic.top-k``
     - **REQUIRED**: positive Java integer specifying the fixed retrieval candidate window, not a per-request override. The query embedding result carries this value for retrieval callers.

Queries must be nonblank natural-language text. Text is preserved without trimming or keyword-query parsing. The selected query prefix counts toward the shared ``max-input-code-points`` limit. ``reject`` fails an over-limit query; ``truncate`` retains the entire prefix and only as many leading query code points as fit, without splitting surrogate pairs. A prefix that leaves no room for query content, or truncation that leaves only blank query content, fails rather than embedding the prefix alone.

Queries reuse the document deployment's endpoint, protocol, model, revision checks, authentication, dimensions, similarity, normalization, timeouts, response-size bound, and input-limit policy. Each query validates the live Solr schema before calling the existing embedding client. Successful preparation returns the validated vector, the client's model identity, and the compatible document pipeline identifier derived by the configured document processor. Query prefix and top-K settings do **not** enter or change the document pipeline hash, and no separate query pipeline setting is introduced.

Document and query prefixes may differ when the selected model requires asymmetric encoding. Establish their compatibility from the **selected model's contract**, including any required instruction and separator, not from its name or the pipeline identifier. Dataverse does not infer prefixes or model compatibility from a model name. Changing only the query prefix does not require regenerating document vectors if the new prefix remains compatible with their encoding contract.

Model Identity, Transport, and Service Responsibilities
+++++++++++++++++++++++++++++++++++++++++++++++++++++++

For **Ollama**, Dataverse checks ``GET /api/tags`` before and after ``POST /api/embed``, preserving any reverse-proxy base path. The configured model must appear by exact ``name`` or ``model`` and its SHA-256 ``digest`` must match ``model-revision`` on both checks. Inventory digests may contain the ``sha256:`` prefix or just the hexadecimal hash; hexadecimal comparison is case-insensitive. The embedding response must also return the exact configured model name. The stored ``embedder`` is ``<model>@<model-revision>``. The digest must come from the actual deployment; do not substitute a guessed Qwen digest.

These checks trust the service's inventory responses; they do **not** attest which model was actually loaded for inference or eliminate races between requests. Ollama endpoints must therefore use a fixed model deployment with homogeneous replicas, consistent routing, and no model replacement during indexing. A load balancer routing inventory and embedding requests to different model revisions breaks this contract even if individual requests succeed.

For **OpenAI-compatible** services, Dataverse sends ``model``, the prepared ``input``, and ``encoding_format=float`` to ``POST /v1/embeddings``. The returned ``model`` must exactly match the configured name. Only that returned name is recorded in ``embedder``. Configuring ``model-revision`` is rejected, because the standard response does not attest an immutable digest or revision. Keeping the endpoint/model immutable, including homogeneous replicas and consistent routing, is the embedding service deployment's responsibility; Dataverse does not verify a model digest for this protocol.

For either protocol, the response must contain exactly one vector (at index zero for OpenAI-compatible responses), of the configured length, with numeric, finite components representable as floats. ``l2`` rejects a zero or nonfinite norm and otherwise normalizes before storing the float vector. ``none`` leaves components unnormalized before conversion to floats. For cosine similarity, the converted vector must additionally have a positive finite float-accumulated squared norm; vectors whose norms underflow or overflow are rejected, not silently normalized.

HTTP is supported for local deployments, but a bearer token sent over HTTP is unencrypted; use HTTPS for bearer authentication. Protect the runtime token and endpoint access. Prepared dataset metadata and natural-language query text are sent to the configured embedding service, so select a service consistent with deployment privacy, cost, and availability requirements. Redirects are disabled for service and live-schema requests. Embedding failures use sanitized messages rather than raw input, tokens, endpoints, response bodies, or transport exception details.

Basic Document Processing and Input Limits
++++++++++++++++++++++++++++++++++++++++++

``basic-v1`` uses the historical source-field concatenation contract, without a document-ID fallback. It reads the already-built, version-specific dataset Solr document in exactly this field order:

#. ``title``
#. ``name``
#. ``nameSort``
#. ``citation``
#. ``dsDescriptionValue``
#. ``description``
#. ``subject``
#. ``subjects``
#. ``authorName``
#. ``dsPersistentId``

Collections are flattened recursively. Lists and other non-set collections retain their supplied order; values from sets are flattened and sorted using Java string ordering for determinism. Null values and strings for which Java ``isBlank()`` is true are skipped. All other indexed text is preserved without trimming, relabeling, deduplication, or additional HTML stripping. In particular, ``TEXTBOX`` HTML stripping has already occurred in the existing keyword indexer. Duplicate values, including values appearing in different source fields, are retained. Values are joined with a single LF (``\n``), without labels or a trailing separator. No document ID is substituted when source values are absent; an empty source produces no embedding, even when a literal prefix is configured.

The processor prepends the selected literal prefix and then checks ``max-input-code-points`` against the complete text. With ``reject``, an overflow fails embedding generation. With ``truncate``, it retains the prefix exactly and takes only as many leading content code points as fit; it does not split Unicode surrogate pairs. If the prefix leaves no room for metadata, generation fails. If the truncated content is blank, no embedding is generated.

Ollama requests always set ``truncate=false`` to disable additional server-side truncation, including when Dataverse itself uses ``input-limit-action=truncate``. OpenAI-compatible requests have no standard no-truncation guarantee: the service **must preserve the submitted text and reject context overflow**, not silently truncate it. The code-point limit is not a model-token limit. Configure a suitable limit and service overflow behavior so that a vector cannot silently represent different text from the text hashed by Dataverse.

Provenance, Failures, and Reindexing
++++++++++++++++++++++++++++++++++++

After successful text preparation, live schema validation, and embedding generation, Dataverse adds all four fields together:

* ``dataset_embedding``: the validated, optionally normalized float vector.
* ``embedder``: the protocol-specific model identity described above.
* ``embedding_source_hash``: lowercase hexadecimal SHA-256 of the **exact UTF-8 text submitted**, after prefixing and any client-side truncation.
* ``embedding_pipeline``: ``basic-v1-<sha256>`` derived deterministically from the processor's canonical encoding-contract configuration, not an operator-assigned string or setting.

The pipeline hash covers the processor/version, ordered source fields, concatenation and Unicode input-limit rules, protocol, effective document prefix, code-point limit, limit action, normalization, dimensions, and similarity. The canonical manifest length-frames each value using its Java string length before hashing its UTF-8 bytes. Model identity is recorded separately in ``embedder``; endpoint, credentials, and timeout values are not part of this pipeline hash. Query embedding preparation reuses this identifier unchanged to identify compatible document processing; query prefixes are configured separately and are not added to the manifest.

Missing or invalid runtime configuration, schema mismatches or schema-access failures, input-limit rejection, model-identity failures, transport failures, or invalid vectors fail closed **for embeddings only**. The dataset still proceeds through keyword indexing, without any of the four embedding fields, and a sanitized warning identifies the dataset/version for retry by reindexing. Empty prepared content also omits the fields. This does not make an unavailable Solr core usable: the Solr schema must still load for ordinary indexing to succeed.

Reindexing replaces the dataset document, so failed generation, an ineligible state, or a disabled feature flag removes all four old embedding fields rather than leaving a stale vector or provenance behind. Changing or disabling the feature flag does not retroactively modify documents already indexed: existing embeddings remain until those documents are replaced or removed. After correcting failures, reindex the affected datasets to retry; after enabling generation, reindex existing published datasets to populate their embeddings.

Changing dimensions or similarity requires rebuilding the vector index. Changing the model or encoding contract requires regenerating affected embeddings; updating settings alone does not migrate existing vectors. During a configuration change, generation may fail validation and replacement indexing may remove embeddings pending a correctly configured reindex. Coordinate Solr settings, Dataverse settings, service deployment, and reindexing to avoid mixing incompatible model identities or pipelines.

Full Reindex
-------------

There are two ways to perform a full reindex of the Dataverse installation search index. Starting with a "clear" ensures a completely clean index but involves downtime. Reindexing in place doesn't involve downtime but does not ensure a completely clean index (e.g. stale entries from destroyed datasets can remain in the index).

Clear and Reindex
+++++++++++++++++


Index and Database Consistency
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Get a list of all database objects that are missing in Solr, and Solr documents that are missing in the database:

``curl http://localhost:8080/api/admin/index/status``

Remove all Solr documents that are orphaned (i.e. not associated with objects in the database):

``curl http://localhost:8080/api/admin/index/clear-orphans``

Clearing ALL Data from Solr
~~~~~~~~~~~~~~~~~~~~~~~~~~~

Please note that the moment you issue this command, it will appear to end users looking at the root Dataverse installation page that all data is gone! This is because the root Dataverse installation page is powered by the search index.

``curl http://localhost:8080/api/admin/index/clear``

Start Async Reindex
~~~~~~~~~~~~~~~~~~~

Please note that this operation may take hours depending on the amount of data in your system and whether or not you installation is using full-text indexing. More information on this, as well as some reference times, can be found at https://github.com/IQSS/dataverse/issues/50.

``curl http://localhost:8080/api/admin/index``

Reindex in Place
+++++++++++++++++

An alternative to completely clearing the search index is to reindex in place.

Clear Index Timestamps
~~~~~~~~~~~~~~~~~~~~~~

``curl -X DELETE http://localhost:8080/api/admin/index/timestamps``

Start or Continue Async Reindex
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

If indexing stops, this command should pick up where it left off based on which index timestamps have been set, which is why we start by clearing these timestamps above. These timestamps are stored in the ``dvobject`` database table.

``curl http://localhost:8080/api/admin/index/continue``

Manual Reindexing
-----------------

If you have made manual changes to a dataset in the database or wish to reindex a dataset that Solr didn't want to index properly, it is possible to manually reindex Dataverse collections and datasets.

Reindexing Dataverse Collections
++++++++++++++++++++++++++++++++

Dataverse collections must be referenced by database object ID. If you have direct database access an SQL query such as

``select id from dataverse where alias='dataversealias';``

should work, or you may click the Dataverse Software's "Edit" menu and look for *dataverseId=* in the URLs produced by the drop-down. Then, to re-index:

``curl http://localhost:8080/api/admin/index/dataverses/135``

which should return: *{"status":"OK","data":{"message":"starting reindex of dataverse 135"}}*

Reindexing Datasets
++++++++++++++++++++

Datasets may be referenced by persistent ID or by database object ID. To re-index by persistent ID:

``curl http://localhost:8080/api/admin/index/dataset?persistentId=doi:10.5072/FK2/AAA000``

To re-index a dataset by its database ID:

``curl http://localhost:8080/api/admin/index/datasets/7504557``

Clearing a Dataset from Solr
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

This API will clear the Solr entry for the dataset specified. It can be useful if you have reasons to want to hide a published dataset from showing in search results and/or on Collection pages, but don't want to destroy and purge it from the database just yet. 

``curl -X DELETE http://localhost:8080/api/admin/index/datasets/<DATABASE_ID>``

This can be reversed of course by re-indexing the dataset with the API above. 


Manually Querying Solr
----------------------

If you suspect something isn't indexed properly in Solr, you may bypass the Dataverse installation's web interface and query the command line directly to verify what Solr returns:

``curl "http://localhost:8983/solr/collection1/select?q=dsPersistentId:doi:10.15139/S3/HFV0AO"``

to see the JSON you were hopefully expecting to see passed along to the Dataverse installation.

Solr Search Index
=================

A Dataverse installation requires Solr to be operational at all times. If you stop Solr, you should see an error about this on the root Dataverse installation page, which is powered by the search index Solr provides. You can set up Solr by following the steps in our Installation Guide's :doc:`/installation/prerequisites` and :doc:`/installation/config` sections explaining how to configure it. This section you're reading now is about the care and feeding of the search index. PostgreSQL is the "source of truth" and the Dataverse installation will copy data from PostgreSQL into Solr. For this reason, the search index can be rebuilt at any time. Depending on the amount of data you have, this can be a slow process. You are encouraged to experiment with production data to get a sense of how long a full reindexing will take.

.. contents:: Contents:
	:local:

Embedding Vector Configuration
------------------------------

The schema declares an optional ``dataset_embedding`` vector field and three optional, single-valued metadata fields: ``embedder``, ``embedding_source_hash``, and ``embedding_pipeline``. These fields have no DocValues and are not copied into the keyword-search catch-all field. Declaring them does not enable embedding generation or semantic search.

Before enabling embedding generation or semantic search, configure both of these JVM system properties in **Solr**, not the Dataverse/Payara JVM:

* ``solr.embedding.dimension``: the selected model's configured positive output dimension, within the deployed Solr/Lucene version's supported limits.
* ``solr.embedding.similarity``: a Solr-supported similarity function consistent with the model and the document/query normalization strategy.

Supply the properties using ``-Dsolr.embedding.dimension=...`` and ``-Dsolr.embedding.similarity=...`` in Solr's existing ``SOLR_OPTS`` configuration (for example, in ``solr.in.sh``). Replace the ellipses with the actual deployment values; they are not defaults. For the development Docker Compose environment, pass ``SOLR_OPTS`` to the ``dev_solr`` service. Preserve any existing JVM options and configure every Solr node hosting the collection consistently.

The schema defaults to one dimension and cosine similarity so keyword-only installations can load the core without embedding configuration. These startup defaults do not select an embedding model or enable semantic search. Before indexing vectors, explicitly configure dimensions and similarity for the chosen model; live schema validation rejects mismatches with Dataverse configuration. Invalid explicit Solr property values can still prevent core loading. Existing documents remain valid without embedding fields.

Before enabling embedding generation or semantic search, define the encoding contract: included metadata fields and ordering, text preparation, input-length handling, model-specific document/query processing, output dimensions, and normalization. Only then assign its ``embedding_pipeline`` identifier. Record the model identifier, including an immutable revision where available, in ``embedder``. Do not use placeholder model or pipeline identifiers.

Embedding producers must validate document and query vector lengths against the configured Solr dimension. After successful generation, write the vector and all three metadata values together; ``embedding_source_hash`` is the SHA-256 of the exact prepared text submitted for embedding. Replacement documents must omit all four fields on generation failure or ineligibility. Semantic queries must filter by the active ``embedder`` and compatible ``embedding_pipeline`` while retaining authorization and other filters. These producer and query behaviors are integration requirements, not functionality provided by the schema itself.

Changing dimensions or similarity requires rebuilding the vector index. Changing the model or encoding contract requires regenerating affected embeddings; updating JVM properties alone does not migrate existing vectors.

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

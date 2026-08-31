package edu.harvard.iq.dataverse.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.solr.common.SolrInputDocument;

import edu.harvard.iq.dataverse.search.SearchIndexOperation.OperationType;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

@Stateless
public class SearchIndexCoordinator {

    @EJB
    SearchIndexOperationServiceBean operationService;

    public void upsertContent(Collection<SolrInputDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        operationService.enqueueContent(OperationType.UPSERT,
                SearchIndexDocumentCodec.encodeDocuments(documents));
    }

    public void delete(Collection<String> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }

        List<String> contentIds = new ArrayList<>();
        List<String> permissionIds = new ArrayList<>();
        for (String documentId : documentIds) {
            if (documentId.endsWith(IndexServiceBean.discoverabilityPermissionSuffix)) {
                permissionIds.add(documentId);
            } else {
                contentIds.add(documentId);
            }
        }
        if (!contentIds.isEmpty()) {
            operationService.enqueueContent(OperationType.DELETE, SearchIndexDocumentCodec.encodeIds(contentIds));
        }
        if (!permissionIds.isEmpty()) {
            operationService.enqueueSolr(OperationType.DELETE, SearchIndexDocumentCodec.encodeIds(permissionIds));
        }
    }

    public void deleteAll() {
        operationService.enqueueContent(OperationType.DELETE_ALL, "[]");
    }
}

package edu.harvard.iq.dataverse.datavariable;

import java.io.Serializable;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Column;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;

import java.util.Collection;
import java.util.ArrayList;

import edu.harvard.iq.dataverse.FileMetadata;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Entity
@Table(indexes = {@Index(columnList="datavariable_id"), @Index(columnList="filemetadata_id"),
                  @Index(columnList="datavariable_id,filemetadata_id")},
        uniqueConstraints={@UniqueConstraint(columnNames={"datavariable_id", "filemetadata_id"})})
public class VariableMetadata implements Serializable  {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * dataVariable: DataVariable to which this metadata belongs.
     */
    @ManyToOne
    @JoinColumn(nullable=false)
    private DataVariable dataVariable;

    /**
     * fileMetadta: FileMetadata to which this metadata belongs.
     */
    @ManyToOne
    @JoinColumn(nullable=false)
    private FileMetadata fileMetadata;

    /**
     * label: variable label.
     */
    @Column(columnDefinition="TEXT")
    private String label;

    /**
     * literalquestion: literal question, metadata variable field.
     */
    @Column(columnDefinition="TEXT")
    private String literalquestion;

    /**
     * postquestion: post question, metadata variable field.
     */
    @Column(columnDefinition="TEXT")
    private String postquestion;

    /**
     * interviewinstruction: Interview Instruction, metadata variable field.
     */
    @Column(columnDefinition="TEXT")
    private String interviewinstruction;

    /**
     * universe: metadata variable field.
     */
    @Column(columnDefinition="TEXT")
    private String universe;

    /**
     * notes: notes, metadata variable field (CDATA).
     */
    @Column(columnDefinition="TEXT")
    private String notes;

    /**
     * concepts: concepts, metadata variable field (JSON).
     */
    @Column(columnDefinition="TEXT")
    private String concepts;

    /**
     * metadata: additional metadata, metadata variable field (JSON).
     */
    @Column(columnDefinition="TEXT")
    private String metadata;

    /**
     * isweightvar: It defines if variable is a weight variable
     */
    private boolean isweightvar = false;

    /**
     * weighted: It defines if variable is weighted
     */
    private boolean weighted = false;

    /**
     * categoriesMetadata: variable metadata for categories that includes weighted frequencies
     */
    @OneToMany (mappedBy="VariableMetadata", cascade={ CascadeType.REMOVE, CascadeType.MERGE,CascadeType.PERSIST})
    private Collection<CategoryMetadata> categoriesMetadata;

    /**
     * dataVariable: DataVariable with which this variable is weighted.
     */
    @ManyToOne
    @JoinColumn(nullable=true)
    private DataVariable weightvariable;

    public VariableMetadata () {
        categoriesMetadata = new ArrayList<CategoryMetadata>() ;
    }

    public VariableMetadata (DataVariable dataVariable, FileMetadata fileMetadata) {
        this.dataVariable = dataVariable;
        this.fileMetadata = fileMetadata;
        categoriesMetadata = new ArrayList<CategoryMetadata>() ;
        if (dataVariable != null && dataVariable.getIngestMetadata() != null) {
            populateFromIngestMetadata(dataVariable.getIngestMetadata());
        }
    }
    private static String resolveConceptIri(String vocab, String content) {
        if (vocab == null || content == null || !vocab.startsWith("Mlstr_area::")) {
            return "";
        }

        String normalizedValue = normalizeValue(content);
        try {
            String encodedValue = URLEncoder.encode(normalizedValue, StandardCharsets.UTF_8);
            String url = "https://semanticlookup.zbmed.de/ols/api/search?q=" + encodedValue
                    + "&ontology=MAELSTROM&rows=1&exact=false";

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);

            if (response.statusCode() == 200) {
                try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
                    JsonObject obj = reader.readObject();
                    if (obj.containsKey("response")) {
                        JsonObject resp = obj.getJsonObject("response");
                        if (resp.containsKey("docs")) {
                            JsonArray docs = resp.getJsonArray("docs");
                            if (!docs.isEmpty()) {
                                return docs.getJsonObject(0).getString("iri", "");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Temporary debug logging - remove before merge
            java.util.logging.Logger.getLogger(VariableMetadata.class.getName())
                    .warning("Semantic lookup failed for vocab=" + vocab
                            + " content=" + content + ": " + e.getClass().getName()
                            + ": " + e.getMessage());
        }
        return "";
    }

    static String normalizeValue(String value) {
        return value == null ? "" : value.replace("_", " ");
    }

    private void populateFromIngestMetadata(String ingestMetadata) {
        if (ingestMetadata == null || ingestMetadata.isEmpty()) {
            return;
        }

        String universeSection = extractSection(ingestMetadata, "[universe]");
        String conceptsSection = extractSection(ingestMetadata, "[concepts]");
        String notesSection    = extractSection(ingestMetadata, "[notes]");

        // [universe] → this.universe
        if (!universeSection.isBlank()) {
            for (String line : universeSection.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).trim().equals("entityType")) {
                    this.universe = line.substring(eq + 1).trim();
                }
            }
        }

        // [concepts] → this.concepts als JSON-Array
        if (!conceptsSection.isBlank()) {
            JsonArrayBuilder conceptBuilder = Json.createArrayBuilder();
            for (String line : conceptsSection.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key   = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    String vocabURI = resolveConceptIri(key, value);

                    conceptBuilder.add(Json.createObjectBuilder()
                            .add("vocab", key)
                            .add("vocabURI", vocabURI)
                            .add("content", value)
                            .build());
                }
            }
            JsonArray arr = conceptBuilder.build();
            if (!arr.isEmpty()) {
                this.concepts = arr.toString();
            }
        }

        // [notes] → this.metadata (labels + table)
        if (!notesSection.isBlank()) {
            this.metadata = notesSection.trim();
        }
    }

    private static String extractSection(String raw, String sectionHeader) {
        int start = raw.indexOf(sectionHeader);
        if (start < 0) return "";
        start += sectionHeader.length();
        int end = raw.length();
        for (String other : new String[]{"[universe]", "[concepts]", "[notes]"}) {
            if (other.equals(sectionHeader)) continue;
            int pos = raw.indexOf(other, start);
            if (pos >= 0 && pos < end) end = pos;
        }
        return raw.substring(start, end).trim();
    }

    /**
     * Getter and Setter functions:
     */
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public FileMetadata getFileMetadata() {
        return fileMetadata;
    }

    public void setFileMetadata(FileMetadata fileMetadata) {
        this.fileMetadata = fileMetadata;
    }

    public void setDataVariable(DataVariable dataVariable) {
        this.dataVariable = dataVariable;
    }

    public DataVariable getDataVariable() {
        return dataVariable;
    }

    public String getLabel() {
        return this.label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getLiteralquestion() {
        return this.literalquestion;
    }

    public void setLiteralquestion(String literalquestion) {
        this.literalquestion = literalquestion;
    }

    public String getPostquestion() {return this.postquestion;}

    public void setPostquestion(String postquestion) {this.postquestion = postquestion;}

    public String getInterviewinstruction() {
        return this.interviewinstruction;
    }

    public void setInterviewinstruction(String interviewinstruction) {
        this.interviewinstruction = interviewinstruction;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getNotes() { return notes; }

    public String getConcepts() {
        return concepts;
    }

    public void setConcepts(String concepts) {
        this.concepts = concepts;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getUniverse() { return this.universe; }

    public void setUniverse(String universe) {
        this.universe = universe;
    }

    public void setIsweightvar(boolean isweightvar) {
        this.isweightvar = isweightvar;
    }

    public boolean isIsweightvar() {
        return isweightvar;
    }

    public DataVariable getWeightvariable() { return this.weightvariable; }

    public void setWeightvariable(DataVariable weightvariable) { this.weightvariable = weightvariable; }

    public void setWeighted(boolean weighted) {
        this.weighted = weighted;
    }

    public boolean isWeighted() {
        return weighted;
    }

    public Collection<CategoryMetadata> getCategoriesMetadata() {
        return categoriesMetadata;
    }

    public void setCategoriesMetadata(Collection<CategoryMetadata> categoriesMetadata) {
        this.categoriesMetadata = categoriesMetadata;

    }

    @Override
    public int hashCode() {
        int hash = 0;
        hash += (this.id != null ? this.id.hashCode() : 0);
        return hash;
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof VariableMetadata)) {
            return false;
        }

        VariableMetadata other = (VariableMetadata)object;
        if (this.id != other.id ) {
            if (this.id == null || !this.id.equals(other.id)) {
                return false;
            }
        }
        return true;
    }
}

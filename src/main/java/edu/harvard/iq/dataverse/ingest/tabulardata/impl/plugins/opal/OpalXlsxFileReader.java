package edu.harvard.iq.dataverse.ingest.tabulardata.impl.plugins.opal;

import edu.harvard.iq.dataverse.DataTable;
import edu.harvard.iq.dataverse.datavariable.DataVariable;
import edu.harvard.iq.dataverse.datavariable.VariableCategory;
import edu.harvard.iq.dataverse.ingest.tabulardata.TabularDataFileReader;
import edu.harvard.iq.dataverse.ingest.tabulardata.TabularDataIngest;
import edu.harvard.iq.dataverse.ingest.tabulardata.spi.TabularDataFileReaderSpi;
import edu.harvard.iq.dataverse.util.BundleUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Ingest plugin for OPAL XLSX data dictionaries.
 *
 * Structure:
 * - "Variables" sheet → variables + metadata (labels, Mlstr_area, etc.)
 * - "Categories" sheet → category values per variable
 *
 * Temporary storage (ingestMetadata):
 * OPAL fields are stored as key=value blocks in DataVariable.ingestMetadata TODO
 * during ingest and later mapped to VariableMetadata.
 *
 * Sections:
 * [universe] → entityType (→ vm.setUniverse → DDI <universe>)
 * [concepts] → Mlstr_area / additional fields (→ DDI <concept>)
 * [notes]    → labels + table info (→ DDI <notes>) TODO write all data that is not catched in notes
 *
 * Categories are linked via table::variable key.
 */
public class OpalXlsxFileReader extends TabularDataFileReader {

    private static final Logger logger =
            Logger.getLogger(OpalXlsxFileReader.class.getCanonicalName());

    static final String VARIABLES_SHEET_NAME  = "Variables";
    static final String CATEGORIES_SHEET_NAME = "Categories";

    public static final String SECTION_UNIVERSE  = "[universe]";
    public static final String SECTION_CONCEPTS  = "[concepts]";
    public static final String SECTION_NOTES     = "[notes]";

    // Column name constants – Variables sheet (lower-cased for map lookup)
    private static final String COL_TABLE       = "table";
    private static final String COL_NAME        = "name";
    private static final String COL_VALUE_TYPE  = "valuetype";
    private static final String COL_ENTITY_TYPE = "entitytype";
    private static final String COL_LABEL       = "label";
    private static final String PREFIX_LABEL    = "label:";
    private static final String LABEL_EN        = "label:en";
    // Matches mlstr_area:: and mlstr_additional:: after lower-casing
    private static final String PREFIX_MLSTR    = "mlstr_";

    // Column name constants – Categories sheet
    private static final String COL_VARIABLE = "variable";
    private static final String COL_CODE     = "code";
    private static final String COL_MISSING  = "missing";

    public OpalXlsxFileReader(TabularDataFileReaderSpi originator) {
        super(originator);
    }


    @Override
    public TabularDataIngest read(BufferedInputStream stream,
                                  boolean storeWithVariableHeader,
                                  File dataFile) throws IOException {
        if (stream == null) {
            throw new IOException(BundleUtil.getStringFromBundle("ingest.csv.nullStream"));
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(stream)) {
            Sheet variablesSheet = workbook.getSheet(VARIABLES_SHEET_NAME);
            if (variablesSheet == null) {
                throw new IOException("Missing required OPAL sheet: " + VARIABLES_SHEET_NAME);
            }
            Sheet categoriesSheet = workbook.getSheet(CATEGORIES_SHEET_NAME);
            if (categoriesSheet == null) {
                throw new IOException("Missing required OPAL sheet: " + CATEGORIES_SHEET_NAME);
            }
            return ingestOpalDictionary(workbook, variablesSheet, categoriesSheet);
        }
    }

    private TabularDataIngest ingestOpalDictionary(XSSFWorkbook workbook,
                                                   Sheet variablesSheet,
                                                   Sheet categoriesSheet) throws IOException {
        DataTable dataTable = new DataTable();
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

        Row varHeader = variablesSheet.getRow(variablesSheet.getFirstRowNum());
        if (varHeader == null) {
            throw new IOException("No header row found in OPAL Variables sheet.");
        }
        Map<String, Integer> varColMap = buildColumnMap(varHeader, formatter, evaluator);

        Integer nameCol       = varColMap.get(COL_NAME);
        Integer tableCol      = varColMap.get(COL_TABLE);
        Integer valueTypeCol  = varColMap.get(COL_VALUE_TYPE);
        Integer entityTypeCol = varColMap.get(COL_ENTITY_TYPE);
        Integer labelCol      = varColMap.get(COL_LABEL);

        if (nameCol == null) {
            throw new IOException("OPAL Variables sheet must contain a 'name' column.");
        }

        Map<String, Integer> labelLangCols = new LinkedHashMap<>();
        Map<String, Integer> mlstrCols     = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : varColMap.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(PREFIX_LABEL)) {
                labelLangCols.put(key, entry.getValue());
            } else if (key.startsWith(PREFIX_MLSTR)) {
                mlstrCols.put(key, entry.getValue());
            }
        }

        // Recover original mixed-case Mlstr column names for the concepts section
        Map<String, String> mlstrOriginalNames =
                buildOriginalNamesMap(varHeader, formatter, evaluator, PREFIX_MLSTR);

        List<DataVariable>        variables       = new ArrayList<>();
        Map<String, DataVariable> variablesLookup = new LinkedHashMap<>();

        for (int i = variablesSheet.getFirstRowNum() + 1;
             i <= variablesSheet.getLastRowNum(); i++) {

            Row row = variablesSheet.getRow(i);
            String name = getCellValue(row, nameCol, formatter, evaluator);
            if (StringUtils.isBlank(name)) {
                continue;
            }

            DataVariable dv = new DataVariable(variables.size(), dataTable);
            dv.setName(name);
            dv.setLabel(resolveDisplayLabel(row, labelLangCols, labelCol, name, formatter, evaluator));
            applyValueType(dv, valueTypeCol != null
                    ? getCellValue(row, valueTypeCol, formatter, evaluator) : "");

            String table = tableCol != null
                    ? getCellValue(row, tableCol, formatter, evaluator) : "";
            String entityType = entityTypeCol != null
                    ? getCellValue(row, entityTypeCol, formatter, evaluator) : "";

            StringBuilder opalMeta = new StringBuilder();

            if (!StringUtils.isBlank(entityType)) {
                opalMeta.append(SECTION_UNIVERSE).append('\n');
                appendNote(opalMeta, COL_ENTITY_TYPE, entityType);
                opalMeta.append('\n');
            }

            boolean hasConcepts = false;
            StringBuilder conceptBlock = new StringBuilder();
            for (Map.Entry<String, Integer> entry : mlstrCols.entrySet()) {
                String val = getCellValue(row, entry.getValue(), formatter, evaluator);
                if (!StringUtils.isBlank(val)) {
                    String originalName = mlstrOriginalNames.getOrDefault(
                            entry.getKey(), entry.getKey());
                    appendNote(conceptBlock, originalName, val);
                    hasConcepts = true;
                }
            }
            if (hasConcepts) {
                opalMeta.append(SECTION_CONCEPTS).append('\n');
                opalMeta.append(conceptBlock);
                opalMeta.append('\n');
            }

            boolean hasNotes = false;
            StringBuilder notesBlock = new StringBuilder();
            if (!StringUtils.isBlank(table)) {
                appendNote(notesBlock, COL_TABLE, table);
                hasNotes = true;
            }
            for (Map.Entry<String, Integer> entry : labelLangCols.entrySet()) {
                String val = getCellValue(row, entry.getValue(), formatter, evaluator);
                if (!StringUtils.isBlank(val)) {
                    appendNote(notesBlock, entry.getKey(), val);
                    hasNotes = true;
                }
            }
            if (hasNotes) {
                opalMeta.append(SECTION_NOTES).append('\n');
                opalMeta.append(notesBlock);
            }

            if (opalMeta.length() > 0) {
                dv.setIngestMetadata(opalMeta.toString().trim());
            }

            variables.add(dv);
            variablesLookup.put(name.toLowerCase(Locale.ROOT), dv);
            if (!StringUtils.isBlank(table)) {
                variablesLookup.put((table + "::" + name).toLowerCase(Locale.ROOT), dv);
            }
        }

        dataTable.setVarQuantity((long) variables.size());
        dataTable.setDataVariables(variables);
        dataTable.setCaseQuantity(1L);

        applyCategories(categoriesSheet, formatter, evaluator, variablesLookup);

        TabularDataIngest ingestedData = new TabularDataIngest();
        ingestedData.setDataTable(dataTable);
        ingestedData.setTabDelimitedFile(createDummyTabFile(variables.size()));
        return ingestedData;
    }

    private void applyCategories(Sheet categoriesSheet,
                                 DataFormatter formatter,
                                 FormulaEvaluator evaluator,
                                 Map<String, DataVariable> variablesLookup) throws IOException {

        Row header = categoriesSheet.getRow(categoriesSheet.getFirstRowNum());
        if (header == null) {
            throw new IOException("No header row found in OPAL Categories sheet.");
        }
        Map<String, Integer> cols = buildColumnMap(header, formatter, evaluator);

        Integer tableCol    = cols.get(COL_TABLE);
        Integer variableCol = cols.get(COL_VARIABLE);
        Integer nameCol     = cols.get(COL_NAME);
        Integer labelCol    = cols.get(COL_LABEL);
        Integer codeCol     = cols.get(COL_CODE);
        Integer missingCol  = cols.get(COL_MISSING);

        if (tableCol == null || variableCol == null || nameCol == null || labelCol == null) {
            throw new IOException(
                    "OPAL Categories sheet must contain columns: table, variable, name, label.");
        }

        for (int rowNum = categoriesSheet.getFirstRowNum() + 1;
             rowNum <= categoriesSheet.getLastRowNum(); rowNum++) {

            Row row     = categoriesSheet.getRow(rowNum);
            String table    = getCellValue(row, tableCol,    formatter, evaluator);
            String variable = getCellValue(row, variableCol, formatter, evaluator);
            String name     = getCellValue(row, nameCol,     formatter, evaluator);

            if (StringUtils.isBlank(table) || StringUtils.isBlank(variable)
                    || StringUtils.isBlank(name)) {
                continue;
            }

            DataVariable dv = variablesLookup.get(
                    (table + "::" + variable).toLowerCase(Locale.ROOT));
            if (dv == null) {
                dv = variablesLookup.get(variable.toLowerCase(Locale.ROOT));
            }
            if (dv == null) {
                logger.warning("Categories sheet references unknown variable '"
                        + variable + "' in table '" + table + "' – skipping row " + rowNum);
                continue;
            }

            String code    = codeCol    != null ? getCellValue(row, codeCol,    formatter, evaluator) : "";
            String label   =                      getCellValue(row, labelCol,   formatter, evaluator);
            String missing = missingCol != null ? getCellValue(row, missingCol, formatter, evaluator) : "";

            VariableCategory category = new VariableCategory();
            category.setDataVariable(dv);
            category.setValue(StringUtils.isBlank(code) ? name : code);
            category.setLabel(StringUtils.isBlank(label) ? name : label);
            category.setMissing(parseMissingFlag(missing));
            category.setOrder(dv.getCategories().size() + 1);
            dv.getCategories().add(category);
        }
    }

    private Map<String, Integer> buildColumnMap(Row header,
                                                DataFormatter formatter,
                                                FormulaEvaluator evaluator) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < header.getLastCellNum(); i++) {
            String val = getCellValue(header, i, formatter, evaluator)
                    .trim().toLowerCase(Locale.ROOT);
            if (!val.isEmpty()) {
                map.put(val, i);
            }
        }
        return map;
    }

    private Map<String, String> buildOriginalNamesMap(Row header,
                                                      DataFormatter formatter,
                                                      FormulaEvaluator evaluator,
                                                      String lowerPrefix) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < header.getLastCellNum(); i++) {
            String original = getCellValue(header, i, formatter, evaluator).trim();
            if (original.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                map.put(original.toLowerCase(Locale.ROOT), original);
            }
        }
        return map;
    }

    private static String getCellValue(Row row, int col,
                                       DataFormatter formatter,
                                       FormulaEvaluator evaluator) {
        if (row == null) return "";
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        String value = formatter.formatCellValue(cell, evaluator);
        return value == null ? "" : value.trim();
    }

    private String resolveDisplayLabel(Row row,
                                       Map<String, Integer> labelLangCols,
                                       Integer labelCol,
                                       String fallback,
                                       DataFormatter formatter,
                                       FormulaEvaluator evaluator) {
        Integer enCol = labelLangCols.get(LABEL_EN);
        if (enCol != null) {
            String val = getCellValue(row, enCol, formatter, evaluator);
            if (!StringUtils.isBlank(val)) return val;
        }
        if (labelCol != null) {
            String val = getCellValue(row, labelCol, formatter, evaluator);
            if (!StringUtils.isBlank(val)) return val;
        }
        return fallback;
    }

    private static void applyValueType(DataVariable dv, String valueType) {
        if ("integer".equalsIgnoreCase(valueType) || "decimal".equalsIgnoreCase(valueType)) {
            dv.setTypeNumeric();
            dv.setIntervalContinuous();
        } else {
            dv.setTypeCharacter();
            dv.setIntervalDiscrete();
        }
    }

    private static void appendNote(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(value).append('\n');
    }

    private static boolean parseMissingFlag(String token) {
        if (StringUtils.isBlank(token)) return false;
        String t = token.trim().toLowerCase(Locale.ROOT);
        return "1".equals(t) || "true".equals(t) || "yes".equals(t) || "y".equals(t);
    }

    private static File createDummyTabFile(int varCount) throws IOException {
        File tempTabFile = File.createTempFile("opal-dummy-", ".tab");
        try (PrintWriter writer = new PrintWriter(tempTabFile)) {
            StringBuilder row = new StringBuilder();
            for (int i = 0; i < varCount; i++) {
                if (i > 0) row.append('\t');
            }
            writer.println(row);
        }
        return tempTabFile;
    }
}
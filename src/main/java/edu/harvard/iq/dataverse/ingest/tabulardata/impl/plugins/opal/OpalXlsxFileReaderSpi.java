package edu.harvard.iq.dataverse.ingest.tabulardata.impl.plugins.opal;

import edu.harvard.iq.dataverse.ingest.tabulardata.TabularDataFileReader;
import edu.harvard.iq.dataverse.ingest.tabulardata.spi.TabularDataFileReaderSpi;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class OpalXlsxFileReaderSpi extends TabularDataFileReaderSpi {

    private static final String[] FORMAT_NAMES = {"opal-xlsx", "OPAL-XLSX"};
    private static final String[] EXTENSIONS = {"xlsx", "XLSX"};
    private static final String[] MIME_TYPES = {"application/x-opal+xlsx"};

    public OpalXlsxFileReaderSpi() {
        super("HU-IQSS-DVN-project", "1.0", FORMAT_NAMES, EXTENSIONS, MIME_TYPES, OpalXlsxFileReaderSpi.class.getName());
    }

    @Override
    public String getDescription(Locale locale) {
        return "HU-IQSS-Dataverse OPAL XLSX";
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        return source instanceof BufferedInputStream;
    }

    @Override
    public boolean canDecodeInput(BufferedInputStream stream) throws IOException {
        return true;
    }

    @Override
    public boolean canDecodeInput(File file) throws IOException {
        return true;
    }

    @Override
    public TabularDataFileReader createReaderInstance(Object ext) throws IOException {
        return new OpalXlsxFileReader(this);
    }
}

package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.testing.JvmSetting;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.junit.jupiter.api.Assertions.*;

@LocalJvmSettings
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class EmbeddingConfigurationTest {

    static final String DIGEST = "sha256:" + "a".repeat(64);
    private Settings settings;

    @BeforeEach
    void configure() {
        settings = new Settings();
    }

    @AfterEach
    void restore() {
        settings.close();
    }

    @Test
    void loadsOnlyExplicitRuntimeValues() throws Exception {
        EmbeddingConfiguration config = EmbeddingConfiguration.load();
        assertAll(
                () -> assertEquals(URI.create("http://localhost:11434/api/embed"), config.endpoint()),
                () -> assertEquals("ollama", config.protocol()),
                () -> assertEquals("qwen3-embedding:8b", config.model()),
                () -> assertEquals(DIGEST, config.modelRevision().orElseThrow()),
                () -> assertEquals("none", config.authMode()),
                () -> assertTrue(config.authToken().isEmpty()),
                () -> assertEquals(Duration.ofMillis(1000), config.connectTimeout()),
                () -> assertEquals(Duration.ofMillis(2000), config.requestTimeout()),
                () -> assertEquals(1048576, config.maxResponseBytes()),
                () -> assertEquals(4096, config.dimensions()),
                () -> assertEquals("cosine", config.similarity()),
                () -> assertEquals("none", config.normalization()),
                () -> assertEquals("basic-v1", config.processor()),
                () -> assertEquals("", config.documentPrefix()),
                () -> assertEquals(8000, config.maxInputCodePoints()),
                () -> assertEquals("reject", config.inputLimitAction()));
    }

    @Test
    void everyRequiredSettingFailsWhenAbsentOrBlank() {
        List<JvmSettings> required = List.of(
                JvmSettings.EMBEDDING_ENDPOINT, JvmSettings.EMBEDDING_PROTOCOL,
                JvmSettings.EMBEDDING_MODEL, JvmSettings.EMBEDDING_MODEL_REVISION,
                JvmSettings.EMBEDDING_AUTH_MODE, JvmSettings.EMBEDDING_CONNECT_TIMEOUT_MS,
                JvmSettings.EMBEDDING_REQUEST_TIMEOUT_MS, JvmSettings.EMBEDDING_MAX_RESPONSE_BYTES,
                JvmSettings.EMBEDDING_DIMENSIONS,
                JvmSettings.EMBEDDING_SIMILARITY, JvmSettings.EMBEDDING_NORMALIZATION,
                JvmSettings.EMBEDDING_PROCESSOR, JvmSettings.EMBEDDING_DOCUMENT_PREFIX_MODE,
                JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION);
        for (JvmSettings key : required) {
            String original = System.getProperty(key.getScopedKey());
            for (String invalid : new String[]{null, "", " "}) {
                settings.set(key, invalid);
                assertThrows(EmbeddingException.class, EmbeddingConfiguration::load, key.name());
            }
            settings.set(key, original);
        }
    }

    @Test
    void rejectsInvalidChoicesAndNumbersWithoutLeakingValues() {
        Map<JvmSettings, List<String>> invalid = Map.ofEntries(
                Map.entry(JvmSettings.EMBEDDING_PROTOCOL, List.of("other", "OLLAMA")),
                Map.entry(JvmSettings.EMBEDDING_AUTH_MODE, List.of("auto")),
                Map.entry(JvmSettings.EMBEDDING_NORMALIZATION, List.of("auto")),
                Map.entry(JvmSettings.EMBEDDING_SIMILARITY, List.of("other")),
                Map.entry(JvmSettings.EMBEDDING_PROCESSOR, List.of("basic-v2")),
                Map.entry(JvmSettings.EMBEDDING_DOCUMENT_PREFIX_MODE, List.of("auto")),
                Map.entry(JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION, List.of("ignore")),
                Map.entry(JvmSettings.EMBEDDING_DIMENSIONS, List.of("0", "-1", "2147483648", "1.5", "secret-value")),
                Map.entry(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, List.of("0", "-1", "2147483648")),
                Map.entry(JvmSettings.EMBEDDING_MAX_RESPONSE_BYTES, List.of("0", "-1", "2147483648", "1.5", "invalid")),
                Map.entry(JvmSettings.EMBEDDING_CONNECT_TIMEOUT_MS, List.of("0", "-1", "1.5", "9223372036854775808")),
                Map.entry(JvmSettings.EMBEDDING_REQUEST_TIMEOUT_MS, List.of("0", "-1", "invalid")));
        for (Map.Entry<JvmSettings, List<String>> entry : invalid.entrySet()) {
            String original = System.getProperty(entry.getKey().getScopedKey());
            for (String value : entry.getValue()) {
                settings.set(entry.getKey(), value);
                EmbeddingException failure = assertThrows(EmbeddingException.class, EmbeddingConfiguration::load);
                assertNull(failure.getCause());
                assertFalse(failure.getMessage().contains("secret-value"));
            }
            settings.set(entry.getKey(), original);
        }
    }

    @Test
    void rejectsUnsafeOrIncompleteEndpoints() {
        for (String endpoint : List.of(
                "http://user:secret@localhost/api/embed", "http://localhost/api/embed?secret=value",
                "http://localhost/api/embed#secret", "ftp://localhost/api/embed", "/api/embed",
                "http://localhost", "http://localhost/api/embeddings", "http://localhost:0/api/embed",
                "http://localhost:65536/api/embed", "http://[invalid/api/embed")) {
            settings.set(JvmSettings.EMBEDDING_ENDPOINT, endpoint);
            EmbeddingException failure = assertThrows(EmbeddingException.class, EmbeddingConfiguration::load);
            assertFalse(failure.getMessage().contains(endpoint));
            assertFalse(failure.getMessage().contains("secret"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void supportsExplicitHttpBearerAndHttpsProxyEndpoint() throws Exception {
        settings.set(JvmSettings.EMBEDDING_AUTH_MODE, "bearer");
        settings.set(JvmSettings.EMBEDDING_AUTH_TOKEN, "private-token");
        assertEquals("private-token", EmbeddingConfiguration.load().authToken().orElseThrow());
        settings.set(JvmSettings.EMBEDDING_ENDPOINT, "https://example.org/proxy/api/embed");
        EmbeddingConfiguration config = EmbeddingConfiguration.load();
        assertEquals("/proxy/api/embed", config.endpoint().getPath());
        assertFalse(config.toString().contains("private-token"));
        assertFalse(config.toString().contains("example.org"));
    }

    @Test
    void bearerRequiresTokenAndNoneRejectsUnusedToken() {
        settings.set(JvmSettings.EMBEDDING_AUTH_MODE, "bearer");
        for (String token : new String[]{null, "", " ", "token\r\nInjected: value", "token value"}) {
            settings.set(JvmSettings.EMBEDDING_AUTH_TOKEN, token);
            assertThrows(EmbeddingException.class, EmbeddingConfiguration::load);
        }
        settings.set(JvmSettings.EMBEDDING_AUTH_MODE, "none");
        settings.set(JvmSettings.EMBEDDING_AUTH_TOKEN, "private-token");
        EmbeddingException failure = assertThrows(EmbeddingException.class, EmbeddingConfiguration::load);
        assertFalse(failure.getMessage().contains("private-token"));
    }

    @Test
    void ollamaRequiresFullDigest() {
        for (String revision : List.of("latest", "sha256:abc", "a".repeat(64), "sha256:" + "g".repeat(64))) {
            settings.set(JvmSettings.EMBEDDING_MODEL_REVISION, revision);
            assertThrows(EmbeddingException.class, EmbeddingConfiguration::load);
        }
    }

    @Test
    void openAiRejectsRevisionEvenWhenItEqualsModel() throws Exception {
        settings.openAi();
        assertTrue(EmbeddingConfiguration.load().modelRevision().isEmpty());
        assertEquals("openai-compatible", EmbeddingConfiguration.load().protocol());
        for (String revision : List.of(DIGEST, "qwen3-embedding:8b")) {
            settings.set(JvmSettings.EMBEDDING_MODEL_REVISION, revision);
            EmbeddingException failure = assertThrows(EmbeddingException.class, EmbeddingConfiguration::load);
            assertTrue(failure.getMessage().contains("no revision attestation"));
        }
    }

    @Test
    void prefixNoneIsExplicitlyEmptyAndLiteralPreservesWhitespace() throws Exception {
        settings.set(JvmSettings.EMBEDDING_DOCUMENT_PREFIX, "ignored");
        assertEquals("", EmbeddingConfiguration.load().documentPrefix());
        settings.set(JvmSettings.EMBEDDING_DOCUMENT_PREFIX_MODE, "literal");
        for (String prefix : List.of("  Document:\n", "   ")) {
            settings.set(JvmSettings.EMBEDDING_DOCUMENT_PREFIX, prefix);
            assertEquals(prefix, EmbeddingConfiguration.load().documentPrefix());
        }
        for (String prefix : new String[]{null, ""}) {
            settings.set(JvmSettings.EMBEDDING_DOCUMENT_PREFIX, prefix);
            assertThrows(EmbeddingException.class, EmbeddingConfiguration::load);
        }
    }

    @Test
    @JvmSetting(key = JvmSettings.EMBEDDING_NORMALIZATION, value = "l2")
    @JvmSetting(key = JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION, value = "truncate")
    void respectsLocalJvmSettingOverrides() throws Exception {
        assertEquals("l2", EmbeddingConfiguration.load().normalization());
        assertEquals("truncate", EmbeddingConfiguration.load().inputLimitAction());
    }

    /** Shared explicit test deployment, restoring all touched system properties. */
    static final class Settings implements AutoCloseable {
        private final Map<JvmSettings, String> previous = new EnumMap<>(JvmSettings.class);

        Settings() {
            for (JvmSettings setting : JvmSettings.values()) {
                if (setting.name().startsWith("EMBEDDING_")) {
                    previous.put(setting, System.getProperty(setting.getScopedKey()));
                    System.clearProperty(setting.getScopedKey());
                }
            }
            set(JvmSettings.EMBEDDING_ENDPOINT, "http://localhost:11434/api/embed");
            set(JvmSettings.EMBEDDING_PROTOCOL, "ollama");
            set(JvmSettings.EMBEDDING_MODEL, "qwen3-embedding:8b");
            set(JvmSettings.EMBEDDING_MODEL_REVISION, DIGEST);
            set(JvmSettings.EMBEDDING_AUTH_MODE, "none");
            set(JvmSettings.EMBEDDING_CONNECT_TIMEOUT_MS, "1000");
            set(JvmSettings.EMBEDDING_REQUEST_TIMEOUT_MS, "2000");
            set(JvmSettings.EMBEDDING_MAX_RESPONSE_BYTES, "1048576");
            set(JvmSettings.EMBEDDING_DIMENSIONS, "4096");
            set(JvmSettings.EMBEDDING_SIMILARITY, "cosine");
            set(JvmSettings.EMBEDDING_NORMALIZATION, "none");
            set(JvmSettings.EMBEDDING_PROCESSOR, "basic-v1");
            set(JvmSettings.EMBEDDING_DOCUMENT_PREFIX_MODE, "none");
            set(JvmSettings.EMBEDDING_MAX_INPUT_CODE_POINTS, "8000");
            set(JvmSettings.EMBEDDING_INPUT_LIMIT_ACTION, "reject");
        }

        void set(JvmSettings key, String value) {
            if (value == null) {
                System.clearProperty(key.getScopedKey());
            } else {
                System.setProperty(key.getScopedKey(), value);
            }
        }

        void openAi() {
            set(JvmSettings.EMBEDDING_PROTOCOL, "openai-compatible");
            set(JvmSettings.EMBEDDING_ENDPOINT, "https://example.invalid/v1/embeddings");
            set(JvmSettings.EMBEDDING_MODEL_REVISION, null);
        }

        @Override
        public void close() {
            previous.forEach(this::set);
        }
    }
}

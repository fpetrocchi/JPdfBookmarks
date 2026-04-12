package it.flavianopetrocchi.jpdfbookmarks.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Salva in {@code ai_debug/} (sotto {@code user.dir}) artefatti per diagnosi di ogni tentativo di estrazione indice:
 * {@code payload_request_[timestamp].json} e {@code ocr_debug_[timestamp].txt}. I file non vengono mai cancellati
 * automaticamente in caso di errore dell'estrazione.
 * <p>
 * Disattivazione: {@code -Djpdfbookmarks.ai.debug.persist=false}
 */
public final class AiExtractionDebugRecorder {

    /** Se {@code false}, non vengono scritti {@code payload_request_*} né {@code ocr_debug_*}. Default {@code true}. */
    public static final String SYSTEM_PROPERTY_DEBUG_PERSIST = "jpdfbookmarks.ai.debug.persist";

    private static final Logger LOG = Logger.getLogger(AiExtractionDebugRecorder.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Campo {@code imageBase64} nel JSON process-index (nessun {@code "} nel valore base64 standard). */
    private static final Pattern IMAGE_BASE64_JSON_FIELD =
            Pattern.compile("\"imageBase64\"\\s*:\\s*\"([A-Za-z0-9+/=]*)\"");

    private AiExtractionDebugRecorder() {}

    public static boolean isPersistenceEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(SYSTEM_PROPERTY_DEBUG_PERSIST, "true"));
    }

    public static Path debugDirectory() {
        return Paths.get(System.getProperty("user.dir", ".")).resolve("ai_debug");
    }

    /**
     * Crea {@code ai_debug} se mancante, scrive payload (con {@code imageBase64} accorciato in didascalia se presente)
     * e testo layer indice. Non propaga eccezioni: errori solo in log.
     *
     * @param timestamp      suffisso file (es. {@code yyyyMMdd_HHmmss})
     * @param payloadJson    corpo JSON come inviato o equivalente descrittivo locale
     * @param textLayerLines testo estratto dalle pagine indice (PDFTextStripper); può essere vuoto
     */
    public static void tryWriteExtractionDebug(String timestamp, String payloadJson, String textLayerLines) {
        if (!isPersistenceEnabled()) {
            return;
        }
        if (timestamp == null || timestamp.isBlank()) {
            return;
        }
        try {
            Path dir = debugDirectory();
            Files.createDirectories(dir);
            String safeTs = timestamp.trim().replaceAll("[^0-9A-Za-z._-]", "_");

            String payloadForDisk = redactLargeBase64Field(payloadJson != null ? payloadJson : "");
            Path payloadPath = dir.resolve("payload_request_" + safeTs + ".json");
            Files.writeString(payloadPath, payloadForDisk, StandardCharsets.UTF_8);

            String ocrHeader =
                    "# Index text layer (Apache PDFBox PDFTextStripper on the selected index pages).\r\n"
                            + "# This is selectable text from the PDF, not optical character recognition (OCR).\r\n"
                            + "# Empty or short output is normal for scanned-only indexes.\r\n\r\n";
            Path ocrPath = dir.resolve("ocr_debug_" + safeTs + ".txt");
            Files.writeString(
                    ocrPath,
                    ocrHeader + (textLayerLines != null ? textLayerLines : ""),
                    StandardCharsets.UTF_8);

            Path abs = dir.toAbsolutePath().normalize();
            LOG.log(
                    Level.INFO,
                    "JPdfBookmarks AI debug: saved extraction diagnostics under "
                            + abs
                            + " — files: "
                            + payloadPath.getFileName()
                            + " , "
                            + ocrPath.getFileName());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "JPdfBookmarks AI debug: could not write ai_debug extraction files", e);
        }
    }

    /**
     * Sostituisce il valore di {@code imageBase64} con una didascalia (senza parsare l'intero megabyte di base64 in un
     * albero Jackson). Poi pretty-print se il risultato è JSON oggetto.
     */
    static String redactLargeBase64Field(String json) {
        if (json == null || json.isBlank()) {
            return "{}\n";
        }
        String redacted = json;
        Matcher m = IMAGE_BASE64_JSON_FIELD.matcher(json);
        if (m.find()) {
            int len = m.group(1).length();
            redacted =
                    m.replaceFirst(
                            Matcher.quoteReplacement(
                                    "\"imageBase64\": \"<omitted: " + len + " base64 characters>\""));
        }
        try {
            JsonNode root = MAPPER.readTree(redacted);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return redacted;
        }
    }
}

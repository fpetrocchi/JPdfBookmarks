/*
 * AiExtractionDebugRecorder.java
 *
 * Copyright (c) 2010 Flaviano Petrocchi <flavianopetrocchi at gmail.com>.
 * All rights reserved.
 *
 * This file is part of JPdfBookmarks.
 *
 * JPdfBookmarks is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPdfBookmarks is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JPdfBookmarks.  If not, see <http://www.gnu.org/licenses/>.
 */

package it.flavianopetrocchi.jpdfbookmarks.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Salva in {@code ai_debug/} (sotto {@code user.dir}) artefatti per diagnosi di ogni tentativo di estrazione indice:
 * {@code payload_request_[timestamp].json}, {@code ocr_debug_[timestamp].txt}, le PNG inviate a Supabase
 * ({@code debug_page_*_[timestamp]_supabase.png}, affiancabili alle PNG di {@code -Djpdfbookmarks.ai.debug=true}) e, dopo la
 * risposta HTTP {@code process-index}, {@code debug_supabase_response_[timestamp].json}. I file non vengono mai cancellati
 * automaticamente in caso di errore dell'estrazione.
 * <p>
 * Disattivazione: {@code -Djpdfbookmarks.ai.debug.persist=false}
 */
public final class AiExtractionDebugRecorder {

    /**
     * Se {@code false}, non vengono scritti {@code payload_request_*}, {@code ocr_debug_*}, PNG cloud né
     * {@code debug_supabase_response_*}. Default {@code true}.
     */
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
     * Scrive il corpo JSON grezzo (o testo) della risposta Supabase {@code process-index}, con lo stesso suffisso
     * temporale di {@link #tryWriteExtractionDebug}. Non propaga eccezioni.
     *
     * @param timestamp     stesso identificatore usato per {@code payload_request_*} (es. {@code yyyyMMdd_HHmmss})
     * @param responseBody  corpo della risposta HTTP 2xx; tipicamente JSON
     */
    /**
     * Salva la risposta grezza di {@code GET check-payment} quando il pagamento risulta confermato ma l'elenco
     * segnalibri resta vuoto dopo il parsing (diagnosi contratto JSON / deserializzazione).
     */
    public static void tryWriteCheckPaymentDebug(String taskId, String responseBody) {
        if (!isPersistenceEnabled()) {
            return;
        }
        if (responseBody == null || responseBody.isBlank()) {
            return;
        }
        try {
            Path dir = debugDirectory();
            Files.createDirectories(dir);
            String safeId =
                    (taskId != null ? taskId.trim().replaceAll("[^0-9A-Za-z._-]", "_") : "unknown")
                            .replaceAll("^_+", "");
            if (safeId.isBlank()) {
                safeId = "unknown";
            }
            Path out = dir.resolve("debug_check_payment_" + safeId + ".json");
            Files.writeString(out, prettifyJsonIfPossible(responseBody), StandardCharsets.UTF_8);
            LOG.log(
                    Level.INFO,
                    "JPdfBookmarks AI debug: saved check-payment response under "
                            + dir.toAbsolutePath().normalize()
                            + " — "
                            + out.getFileName());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "JPdfBookmarks AI debug: could not write debug_check_payment file", e);
        }
    }

    public static void tryWriteSupabaseResponseDebug(String timestamp, String responseBody) {
        if (!isPersistenceEnabled()) {
            return;
        }
        if (timestamp == null || timestamp.isBlank()) {
            return;
        }
        if (responseBody == null) {
            return;
        }
        try {
            Path dir = debugDirectory();
            Files.createDirectories(dir);
            String safeTs = timestamp.trim().replaceAll("[^0-9A-Za-z._-]", "_");
            Path out = dir.resolve("debug_supabase_response_" + safeTs + ".json");
            String content = prettifyJsonIfPossible(responseBody);
            Files.writeString(out, content, StandardCharsets.UTF_8);
            LOG.log(
                    Level.INFO,
                    "JPdfBookmarks AI debug: saved Supabase process-index response under "
                            + dir.toAbsolutePath().normalize()
                            + " — "
                            + out.getFileName());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "JPdfBookmarks AI debug: could not write debug_supabase_response file", e);
        }
    }

    /**
     * Salva le PNG effettivamente usate per {@code imageBase64} verso Supabase (stesso {@code timestamp} degli altri
     * file in {@code ai_debug/}). Convenzione nomi allineata a {@link AiOrchestrator} con
     * {@code -Djpdfbookmarks.ai.debug=true}.
     * <p>
     * Se {@code perPagePngs} non è {@code null} e contiene più di un elemento, salva anche ogni pagina come
     * {@code debug_page_<n>_<timestamp>_supabase_source.png} oltre all'immagine unita inviata
     * ({@code debug_page_<from>-<to>_stacked_<timestamp>_supabase.png}).
     */
    public static void tryWriteCloudIndexPngs(
            String timestamp,
            int startPage,
            int endPage,
            byte[] pngSentToSupabase,
            List<byte[]> perPagePngs) {
        if (!isPersistenceEnabled()) {
            return;
        }
        if (timestamp == null || timestamp.isBlank()) {
            return;
        }
        if (pngSentToSupabase == null || pngSentToSupabase.length == 0) {
            return;
        }
        try {
            Path dir = debugDirectory();
            Files.createDirectories(dir);
            String safeTs = timestamp.trim().replaceAll("[^0-9A-Za-z._-]", "_");

            if (perPagePngs != null && perPagePngs.size() > 1) {
                for (int i = 0; i < perPagePngs.size(); i++) {
                    byte[] slice = perPagePngs.get(i);
                    if (slice == null || slice.length == 0) {
                        continue;
                    }
                    int pageNum = startPage + i;
                    Path p = dir.resolve("debug_page_" + pageNum + "_" + safeTs + "_supabase_source.png");
                    Files.write(p, slice);
                }
                Path stacked =
                        dir.resolve(
                                "debug_page_"
                                        + startPage
                                        + "-"
                                        + endPage
                                        + "_stacked_"
                                        + safeTs
                                        + "_supabase.png");
                Files.write(stacked, pngSentToSupabase);
            } else if (endPage > startPage) {
                Path p = dir.resolve("debug_page_" + startPage + "-" + endPage + "_" + safeTs + "_supabase.png");
                Files.write(p, pngSentToSupabase);
            } else {
                Path p = dir.resolve("debug_page_" + startPage + "_" + safeTs + "_supabase.png");
                Files.write(p, pngSentToSupabase);
            }

            LOG.log(
                    Level.INFO,
                    "JPdfBookmarks AI debug: saved cloud index PNG(s) under "
                            + dir.toAbsolutePath().normalize()
                            + " (run "
                            + safeTs
                            + ")");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "JPdfBookmarks AI debug: could not write cloud index PNG files", e);
        }
    }

    private static String prettifyJsonIfPossible(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}\n";
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return raw;
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

/*
 * AiOrchestrator.java
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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.ImageContent;
import it.flavianopetrocchi.jpdfbookmarks.ai.agent.BookmarkExtractorAgent;
import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Coordina estrazione testo digitale (PDFTextStripper), rendering delle pagine indice e chiamata al modello multimodale.
 * <p>
 * <strong>Log del JSON grezzo del modello (OpenAI, Ollama, …)</strong> prima del parsing in {@link AiBookmark}:
 * <ul>
 *   <li><em>Metodo semplice:</em> avvia la JVM con {@code -Djpdfbookmarks.ai.logBookmarkJson=true}; il testo viene
 *       loggato a livello {@link Level#INFO} sul logger di questa classe e compare nella console di Run/Debug dell'IDE
 *       o nel terminale (se il root logger è INFO, come di solito).</li>
 *   <li><em>Alternativa:</em> senza proprietà, imposta il livello {@link Level#FINE} per
 *       {@code it.flavianopetrocchi.jpdfbookmarks.ai.service.AiOrchestrator} in {@code logging.properties} o nella vista
 *       Logger dell'IDE.</li>
 * </ul>
 * <p>
 * <strong>Cartella {@code ai_debug}</strong> (sotto {@code user.dir}, di solito la root del progetto in IDE):
 * <ul>
 *   <li>Per ogni tentativo di estrazione vengono sempre scritti (salvo {@code -Djpdfbookmarks.ai.debug.persist=false})
 *       {@code payload_request_*.json}, {@code ocr_debug_*.txt}, per il cloud le PNG inviate a Supabase
 *       ({@code debug_page_*_*_supabase.png} e affini) e {@code debug_supabase_response_*.json} tramite
 *       {@link AiExtractionDebugRecorder}.</li>
 *   <li>Con {@code -Djpdfbookmarks.ai.debug=true} si aggiungono anche altri artefatti testo/PNG per l'estrazione locale.</li>
 * </ul>
 */
public class AiOrchestrator {

    /**
     * Se {@code true}, scrive in {@code ai_debug/} file {@code debug_text_*.txt} e {@code debug_page_*_*.png}.
     * JVM: {@code -Djpdfbookmarks.ai.debug=true}
     */
    public static final String SYSTEM_PROPERTY_AI_DEBUG = "jpdfbookmarks.ai.debug";

    /**
     * Se {@code true}, la risposta testuale del modello viene loggata a INFO (visibile con la configurazione predefinita).
     * JVM: {@code -Djpdfbookmarks.ai.logBookmarkJson=true}
     */
    public static final String SYSTEM_PROPERTY_LOG_BOOKMARK_JSON = "jpdfbookmarks.ai.logBookmarkJson";

    private static final Logger LOG = Logger.getLogger(AiOrchestrator.class.getName());

    private static final ObjectMapper BOOKMARK_JSON = new ObjectMapper();

    private final PdfVisionService visionService;
    private final BookmarkExtractorAgent extractorAgent;
    /** Se true, le pagine indice vengono unite in un'unica immagine (es. Ollama llama3.2-vision: una sola immagine per richiesta). */
    private final boolean stackIndexPagesForVision;
    private final SupabaseAiClient cloudClient;
    /** Invito a {@code process-index} ({@code standard} / {@code advanced}); usato solo se {@link #cloudClient} non è null. */
    private final String cloudProcessIndexModel;

    public AiOrchestrator(PdfVisionService visionService, BookmarkExtractorAgent extractorAgent) {
        this(visionService, extractorAgent, false, null, null);
    }

    public AiOrchestrator(
            PdfVisionService visionService,
            BookmarkExtractorAgent extractorAgent,
            boolean stackIndexPagesForVision) {
        this(visionService, extractorAgent, stackIndexPagesForVision, null, null);
    }

    /**
     * @param cloudClient se non {@code null}, {@link #processIndex} invia testo e immagini al servizio cloud invece
     *                    del modello locale; {@code extractorAgent} può essere {@code null}.
     */
    public AiOrchestrator(
            PdfVisionService visionService,
            BookmarkExtractorAgent extractorAgent,
            boolean stackIndexPagesForVision,
            SupabaseAiClient cloudClient) {
        this(visionService, extractorAgent, stackIndexPagesForVision, cloudClient, null);
    }

    /**
     * Come {@link #AiOrchestrator(PdfVisionService, BookmarkExtractorAgent, boolean, SupabaseAiClient)} con
     * {@code model} passato a {@link SupabaseAiClient#submitProcessIndex(int, int, String, byte[], String)}.
     */
    public AiOrchestrator(
            PdfVisionService visionService,
            BookmarkExtractorAgent extractorAgent,
            boolean stackIndexPagesForVision,
            SupabaseAiClient cloudClient,
            String cloudProcessIndexModel) {
        this.visionService = Objects.requireNonNull(visionService, "visionService");
        this.stackIndexPagesForVision = stackIndexPagesForVision;
        this.cloudClient = cloudClient;
        this.cloudProcessIndexModel = cloudProcessIndexModel;
        if (cloudClient == null) {
            this.extractorAgent = Objects.requireNonNull(extractorAgent, "extractorAgent");
        } else {
            this.extractorAgent = extractorAgent;
        }
    }

    /** @return {@code true} se l'estrazione indice passa da {@link SupabaseAiClient}. */
    public boolean usesCloudService() {
        return cloudClient != null;
    }

    /**
     * Esegue il flusso completo: testo digitale + immagini delle pagine → modello → lista strutturata di segnalibri.
     *
     * @param document   PDF aperto (non viene chiuso)
     * @param startPage  prima pagina dell'indice, 1-based inclusiva
     * @param endPage    ultima pagina dell'indice, 1-based inclusiva
     * @return segnalibri e/o metadati task cloud ({@link ProcessIndexResult})
     * @throws AiOrchestrationException in caso di errore di rendering o di fallimento dell'IA
     */
    public ProcessIndexResult processIndex(PDDocument document, int startPage, int endPage)
            throws AiOrchestrationException {
        return processIndex(document, startPage, endPage, null);
    }

    /**
     * @param extendPaidTaskId opzionale (solo cloud): UUID task già pagato perrieseguire l'estrazione su più pagine
     *                         indice senza nuovo pagamento.
     */
    public ProcessIndexResult processIndex(
            PDDocument document, int startPage, int endPage, String extendPaidTaskId)
            throws AiOrchestrationException {
        Objects.requireNonNull(document, "document");
        if (cloudClient != null) {
            return processIndexViaCloud(document, startPage, endPage, extendPaidTaskId);
        }
        if (extendPaidTaskId != null && !extendPaidTaskId.isBlank()) {
            throw new AiOrchestrationException(
                    "L'estensione dell'indice dopo il pagamento è disponibile solo in modalità cloud.", null);
        }
        Objects.requireNonNull(extractorAgent, "extractorAgent");

        String indexPlainText = extractIndexPlainTextForAi(document, startPage, endPage);

        List<Image> images;
        try {
            if (stackIndexPagesForVision && endPage > startPage) {
                images =
                        List.of(
                                visionService.renderIndexPagesAsSingleStackedLangChainImage(
                                        document, startPage, endPage));
            } else {
                images = visionService.renderPagesAsLangChainImages(document, startPage, endPage);
            }
        } catch (IllegalArgumentException ex) {
            throw new AiOrchestrationException(
                    "Intervallo di pagine non valido per l'indice: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new AiOrchestrationException(
                    "Non è stato possibile convertire le pagine dell'indice in immagini per l'analisi. "
                            + "Verifica che il PDF non sia danneggiato o protetto in modo incompatibile e riprova.",
                    ex);
        }

        String debugRunId =
                DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        writeAiDebugArtifacts(indexPlainText, images, startPage, endPage, debugRunId);
        boolean stackedVision = stackIndexPagesForVision && endPage > startPage;
        AiExtractionDebugRecorder.tryWriteExtractionDebug(
                debugRunId,
                buildLocalExtractionDebugPayloadJson(
                        startPage, endPage, indexPlainText, images.size(), stackedVision),
                indexPlainText);

        List<ImageContent> pageContents = images.stream().map(ImageContent::from).toList();
        try {
            String raw = extractorAgent.extractBookmarks(indexPlainText, pageContents);
            logRawModelResponseForDebug(raw);
            return ProcessIndexResult.localBookmarks(parseBookmarksJson(raw));
        } catch (AiOrchestrationException ex) {
            throw ex;
        } catch (Exception ex) {
            if (!stackIndexPagesForVision && isSingleImageOnlyVisionError(ex) && pageContents.size() > 1) {
                try {
                    Image stacked =
                            visionService.renderIndexPagesAsSingleStackedLangChainImage(
                                    document, startPage, endPage);
                    writeAiDebugStackedImage(stacked, startPage, endPage, debugRunId);
                    String raw =
                            extractorAgent.extractBookmarks(
                                    indexPlainText, List.of(ImageContent.from(stacked)));
                    logRawModelResponseForDebug(raw);
                    return ProcessIndexResult.localBookmarks(parseBookmarksJson(raw));
                } catch (AiOrchestrationException ex2) {
                    throw ex2;
                } catch (Exception ex2) {
                    throw new AiOrchestrationException(
                            "L'estrazione automatica dei segnalibri non è riuscita neppure unendo le pagine in un'unica "
                                    + "immagine (richiesto da questo modello). Controlla Ollama, la memoria disponibile e "
                                    + "prova con meno pagine nell'intervallo.",
                            ex2);
                }
            }
            if (isVisionHttpTimeout(ex)) {
                throw new AiOrchestrationException(
                        "Timeout in attesa della risposta dal servizio di IA (spesso Ollama con modelli vision su immagini "
                                + "grandi). Riduci le pagine dell'indice, libera RAM se il sistema è al limite, oppure prova un "
                                + "modello più leggero.",
                        ex);
            }
            throw new AiOrchestrationException(
                    "L'estrazione automatica dei segnalibri non è riuscita. "
                            + "Controlla la connessione al servizio di IA, la configurazione (API key, modello, Ollama, ecc.) "
                            + "e riprova.",
                    ex);
        }
    }

    private ProcessIndexResult processIndexViaCloud(
            PDDocument document, int startPage, int endPage, String extendPaidTaskId)
            throws AiOrchestrationException {
        String indexPlainText = extractIndexPlainTextForAi(document, startPage, endPage);
        List<Image> images;
        try {
            if (stackIndexPagesForVision && endPage > startPage) {
                images =
                        List.of(
                                visionService.renderIndexPagesAsSingleStackedLangChainImage(
                                        document, startPage, endPage));
            } else {
                images = visionService.renderPagesAsLangChainImages(document, startPage, endPage);
            }
        } catch (IllegalArgumentException ex) {
            throw new AiOrchestrationException(
                    "Intervallo di pagine non valido per l'indice: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new AiOrchestrationException(
                    "Non è stato possibile convertire le pagine dell'indice in immagini per il servizio cloud. "
                            + "Verifica che il PDF non sia danneggiato o protetto in modo incompatibile e riprova.",
                    ex);
        }
        String debugRunId =
                DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        writeAiDebugArtifacts(indexPlainText, images, startPage, endPage, debugRunId);
        List<byte[]> pngs = new ArrayList<>();
        for (Image img : images) {
            try {
                pngs.add(langChainImageToPngBytes(img));
            } catch (IOException e) {
                throw new AiOrchestrationException("Impossibile codificare le immagini indice per il cloud.", e);
            }
        }
        final byte[] singlePng;
        try {
            if (pngs.size() == 1) {
                singlePng = pngs.get(0);
            } else {
                Image stacked =
                        visionService.renderIndexPagesAsSingleStackedLangChainImage(
                                document, startPage, endPage);
                writeAiDebugStackedImage(stacked, startPage, endPage, debugRunId);
                singlePng = langChainImageToPngBytes(stacked);
            }
        } catch (IOException e) {
            throw new AiOrchestrationException(
                    "Impossibile unire le pagine indice in un'unica immagine per il servizio cloud.", e);
        }
        AiExtractionDebugRecorder.tryWriteCloudIndexPngs(
                debugRunId, startPage, endPage, singlePng, pngs.size() > 1 ? pngs : null);
        String model =
                cloudProcessIndexModel != null && !cloudProcessIndexModel.isBlank()
                        ? cloudProcessIndexModel.trim().toLowerCase(Locale.ROOT)
                        : "standard";
        if (!"standard".equals(model) && !"advanced".equals(model)) {
            model = "standard";
        }
        String cloudJson =
                cloudClient.buildProcessIndexJsonPayload(
                        startPage, endPage, indexPlainText, singlePng, model, extendPaidTaskId);
        AiExtractionDebugRecorder.tryWriteExtractionDebug(debugRunId, cloudJson, indexPlainText);
        CloudIndexResult cloudResult = cloudClient.submitProcessIndexWithJson(cloudJson, debugRunId);
        if (extendPaidTaskId != null && !extendPaidTaskId.isBlank()) {
            return ProcessIndexResult.fromCloudPaidExtension(cloudResult);
        }
        return ProcessIndexResult.fromCloudProcessIndex(cloudResult);
    }

    /**
     * JSON descrittivo per {@code payload_request_*.json} in estrazione locale (nessuna Edge Function).
     */
    private static String buildLocalExtractionDebugPayloadJson(
            int startPage,
            int endPage,
            String indexPlainText,
            int renderedImageCount,
            boolean stackedSingleImageForVision) {
        try {
            ObjectNode root = BOOKMARK_JSON.createObjectNode();
            root.put("extractionMode", "local");
            root.put("start_page", startPage);
            root.put("end_page", endPage);
            root.put("textLayerContent", indexPlainText != null ? indexPlainText : "");
            root.put("renderedImageCount", renderedImageCount);
            root.put("stackedSingleImageForVision", stackedSingleImageForVision);
            return BOOKMARK_JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"extractionMode\":\"local\",\"debugPayloadError\":true}\n";
        }
    }

    private static boolean isAiDebugEnabled() {
        return Boolean.parseBoolean(System.getProperty(SYSTEM_PROPERTY_AI_DEBUG, "false"));
    }

    private static Path aiDebugDirectory() {
        return Paths.get(System.getProperty("user.dir", ".")).resolve("ai_debug");
    }

    private void writeAiDebugArtifacts(
            String textLayer, List<Image> images, int startPage, int endPage, String timestamp) {
        if (!isAiDebugEnabled() || images == null || images.isEmpty()) {
            return;
        }
        try {
            Path dir = aiDebugDirectory();
            Files.createDirectories(dir);
            Path textFile = dir.resolve("debug_text_" + timestamp + ".txt");
            Files.writeString(textFile, textLayer != null ? textLayer : "", StandardCharsets.UTF_8);

            if (images.size() == 1) {
                String pageKey = endPage > startPage ? startPage + "-" + endPage : String.valueOf(startPage);
                Path png = dir.resolve("debug_page_" + pageKey + "_" + timestamp + ".png");
                Files.write(png, langChainImageToPngBytes(images.get(0)));
            } else {
                for (int i = 0; i < images.size(); i++) {
                    int pageNum = startPage + i;
                    Path png = dir.resolve("debug_page_" + pageNum + "_" + timestamp + ".png");
                    Files.write(png, langChainImageToPngBytes(images.get(i)));
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Scrittura cartella ai_debug non riuscita", e);
        }
    }

    private void writeAiDebugStackedImage(Image stacked, int startPage, int endPage, String timestamp) {
        if (!isAiDebugEnabled() || stacked == null) {
            return;
        }
        try {
            Path dir = aiDebugDirectory();
            Files.createDirectories(dir);
            Path png =
                    dir.resolve("debug_page_" + startPage + "-" + endPage + "_stacked_" + timestamp + ".png");
            Files.write(png, langChainImageToPngBytes(stacked));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Scrittura immagine stacked in ai_debug non riuscita", e);
        }
    }

    private static byte[] langChainImageToPngBytes(Image image) throws IOException {
        String b64 = image.base64Data();
        if (b64 == null || b64.isBlank()) {
            throw new IOException("LangChain4j Image has no base64Data");
        }
        return Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
    }

    /**
     * Testo grezzo delle pagine indice per l'IA. In caso di errore I/O sullo stripper, restituisce stringa vuota e logga.
     */
    private String extractIndexPlainTextForAi(PDDocument document, int startPage, int endPage)
            throws AiOrchestrationException {
        try {
            return visionService.extractTextFromPages(document, startPage, endPage);
        } catch (IllegalArgumentException ex) {
            throw new AiOrchestrationException(
                    "Intervallo di pagine non valido per l'indice: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            LOG.log(
                    Level.WARNING,
                    "Estrazione testo digitale delle pagine indice non riuscita; si continua solo con le immagini.",
                    ex);
            return "";
        }
    }

    private static boolean isVisionHttpTimeout(Throwable t) {
        while (t != null) {
            if (t instanceof dev.langchain4j.exception.TimeoutException) {
                return true;
            }
            if (t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Stampa il testo integrale della risposta del modello (tipicamente JSON) per verificare il mapping verso
     * {@link AiBookmark}. Con {@link #SYSTEM_PROPERTY_LOG_BOOKMARK_JSON}={@code true} usa {@link Level#INFO};
     * altrimenti {@link Level#FINE}.
     */
    private static void logRawModelResponseForDebug(String raw) {
        boolean toInfo = Boolean.parseBoolean(System.getProperty(SYSTEM_PROPERTY_LOG_BOOKMARK_JSON, "false"));
        Level level = toInfo ? Level.INFO : Level.FINE;
        if (!LOG.isLoggable(level)) {
            return;
        }
        LOG.log(
                level,
                "=== Risposta grezza del modello IA (estrazione segnalibri), testo completo prima del parsing ===");
        LOG.log(level, raw == null ? "(null)" : raw);
    }

    /**
     * Ollama (es. llama3.2-vision) risponde con errore se si inviano più immagini in una sola richiesta.
     */
    private static boolean isSingleImageOnlyVisionError(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("only supports one image") || lower.contains("more than one image")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private static List<AiBookmark> parseBookmarksJson(String raw) throws AiOrchestrationException {
        String trimmed = Objects.requireNonNullElse(raw, "").trim();
        if (trimmed.isEmpty()) {
            throw new AiOrchestrationException(
                    "Il modello ha restituito una risposta vuota. Usa un modello con supporto alle immagini (vision), "
                            + "verifica che Ollama sia in esecuzione e che il modello sia installato (es. ollama pull …), "
                            + "oppure controlla la chiave API e il modello OpenAI nelle opzioni.",
                    null);
        }
        String json = stripOptionalMarkdownCodeFence(trimmed);
        if (json.isEmpty()) {
            json = extractJsonArraySubstring(trimmed);
        }
        if (json.isEmpty()) {
            json = trimmed;
        }
        try {
            return BOOKMARK_JSON.readValue(json, new TypeReference<List<AiBookmark>>() {});
        } catch (Exception e) {
            throw new AiOrchestrationException(
                    "Impossibile interpretare la risposta del modello come JSON (array di segnalibri). "
                            + "Verifica che il modello restituisca solo JSON, senza testo o blocchi markdown.",
                    e);
        }
    }

    /**
     * Estrae il primo segmento che assomiglia a un array JSON (da '[' all'ultimo ']'), utile se il modello aggiunge
     * testo esplicativo o fence markdown malformati.
     */
    static String extractJsonArraySubstring(String s) {
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1).trim();
        }
        return "";
    }

    /**
     * Rimuove un eventuale fence {@code ```json ... ```} che alcuni modelli aggiungono intorno al JSON.
     */
    static String stripOptionalMarkdownCodeFence(String s) {
        String t = s.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNl = t.indexOf('\n');
        if (firstNl < 0) {
            return t;
        }
        String body = t.substring(firstNl + 1);
        int lastFence = body.lastIndexOf("```");
        if (lastFence >= 0) {
            body = body.substring(0, lastFence);
        }
        return body.trim();
    }
}

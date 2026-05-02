/*
 * SupabaseAiClient.java
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import it.flavianopetrocchi.jpdfbookmarks.Prefs;
import it.flavianopetrocchi.jpdfbookmarks.Res;
import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for Supabase Edge (or compatible) endpoints: submit index images, optional payment check, fetch bookmarks.
 * <p>
 * La risposta di {@link #submitProcessIndex} è un oggetto JSON con {@code taskId} / {@code extractionId} e
 * {@code bookmarks} (array); il risultato è un {@link CloudIndexResult}.
 */
public final class SupabaseAiClient {

    private static final Logger LOG = Logger.getLogger(SupabaseAiClient.class.getName());

    /**
     * Risposta {@code GET check-payment}: {@code paid}, {@code model_type} quando noto, e segnalibri se inclusi
     * (contratto pdfbookmarks-backend).
     */
    public record CheckPaymentResult(boolean paid, List<AiBookmark> bookmarksWhenPaid, String modelTypeWhenPaid) {
        public CheckPaymentResult {
            bookmarksWhenPaid =
                    bookmarksWhenPaid != null ? List.copyOf(bookmarksWhenPaid) : List.of();
            modelTypeWhenPaid = modelTypeWhenPaid != null ? modelTypeWhenPaid.trim() : "";
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(8);

    private final String processIndexUrl;
    private final String anonKey;
    private final String checkPaymentUrl;
    private final String fetchBookmarksUrl;
    /** GET pubblico (stessi header Supabase) che restituisce JSON catalogo prezzi; vedi {@link StripeCatalogPrices}. */
    private final String stripePricesUrl;
    /** POST verso Edge Function {@code create-checkout}; URL opaco indipendente dal provider di pagamento. */
    private final String createCheckoutUrl;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(45)).build();

    /**
     * Normalizza la chiave anon copiata dal dashboard: rimuove {@code Bearer } se incollato per errore, virgolette e
     * spazi/salti riga interni che invalidano il JWT (errore Supabase «Invalid Token or Protected Header formatting»).
     */
    public static String normalizeSupabasePublicAnonKey(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.length() >= 7 && t.regionMatches(true, 0, "bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        t = t.replaceAll("\\s+", "");
        return t;
    }

    public SupabaseAiClient(
            String processIndexUrl,
            String anonKey,
            String checkPaymentUrl,
            String fetchBookmarksUrl) {
        this(processIndexUrl, anonKey, checkPaymentUrl, fetchBookmarksUrl, "", "");
    }

    public SupabaseAiClient(
            String processIndexUrl,
            String anonKey,
            String checkPaymentUrl,
            String fetchBookmarksUrl,
            String stripePricesUrl) {
        this(processIndexUrl, anonKey, checkPaymentUrl, fetchBookmarksUrl, stripePricesUrl, "");
    }

    public SupabaseAiClient(
            String processIndexUrl,
            String anonKey,
            String checkPaymentUrl,
            String fetchBookmarksUrl,
            String stripePricesUrl,
            String createCheckoutUrl) {
        this.processIndexUrl = Objects.requireNonNull(processIndexUrl, "processIndexUrl").trim();
        this.anonKey = normalizeSupabasePublicAnonKey(Objects.requireNonNull(anonKey, "anonKey"));
        this.checkPaymentUrl = checkPaymentUrl != null ? checkPaymentUrl.trim() : "";
        this.fetchBookmarksUrl = fetchBookmarksUrl != null ? fetchBookmarksUrl.trim() : "";
        this.stripePricesUrl = stripePricesUrl != null ? stripePricesUrl.trim() : "";
        this.createCheckoutUrl = createCheckoutUrl != null ? createCheckoutUrl.trim() : "";
    }

    /**
     * GET opzionale verso {@code stripePricesUrl}: importi correnti (vedi {@link StripeCatalogPrices}). Non propaga
     * eccezioni; su URL vuoto, chiave anon assente, errore HTTP o JSON non valido restituisce {@link Optional#empty()}.
     */
    public Optional<StripeCatalogPrices.Result> tryFetchStripeCatalogPrices() {
        if (stripePricesUrl.isEmpty() || anonKey.isEmpty()) {
            return Optional.empty();
        }
        try {
            String body = httpGet(stripePricesUrl);
            return StripeCatalogPrices.parse(body);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Optional stripe catalog prices request failed", e);
            return Optional.empty();
        }
    }

    /**
     * Costruisce il corpo JSON inviato a {@code process-index} (stesso payload di {@link #submitProcessIndexWithJson}):
     * {@code imageBase64}, {@code textLayerContent} (testo layer come in {@code BookmarkExtractorAgent}), opzionale
     * {@code model} ({@code standard} / {@code advanced}) e {@code tier} per compatibilità backend, e sempre
     * {@code openai_model} uguale a {@link Prefs#ALLOWED_OPENAI_MODEL_FOR_EXTRACTION} affinché la Edge Function usi
     * quel modello OpenAI (se il backend legge questo campo). I campi {@code start_page}/{@code end_page} restano utili in log lato client.
     */
    public String buildProcessIndexJsonPayload(
            int startPage, int endPage, String indexPlainText, byte[] indexPngBytes, String model)
            throws AiOrchestrationException {
        return buildProcessIndexJsonPayload(startPage, endPage, indexPlainText, indexPngBytes, model, null);
    }

    /**
     * @param extendPaidTaskId se non vuoto, invia {@code extend_paid_task_id} perrieseguire l'estrazione sul record già
     *                         pagato con un intervallo di pagine indice più ampio (backend pdfbookmarks).
     */
    public String buildProcessIndexJsonPayload(
            int startPage,
            int endPage,
            String indexPlainText,
            byte[] indexPngBytes,
            String model,
            String extendPaidTaskId)
            throws AiOrchestrationException {
        if (indexPngBytes == null || indexPngBytes.length == 0) {
            throw new AiOrchestrationException("Index image bytes are empty; nothing to send to the cloud.", null);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("start_page", startPage);
        root.put("end_page", endPage);
        root.put("textLayerContent", indexPlainText != null ? indexPlainText : "");
        root.put("imageBase64", Base64.getEncoder().encodeToString(indexPngBytes));
        root.put("mimeType", "image/png");
        if (model != null && !model.isBlank()) {
            String m = model.trim().toLowerCase(Locale.ROOT);
            if ("standard".equals(m) || "advanced".equals(m)) {
                root.put("model", m);
                root.put("tier", m);
            }
        }
        root.put("openai_model", Prefs.ALLOWED_OPENAI_MODEL_FOR_EXTRACTION);
        if (extendPaidTaskId != null && !extendPaidTaskId.isBlank()) {
            root.put("extend_paid_task_id", extendPaidTaskId.trim());
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new AiOrchestrationException("Failed to build cloud request JSON.", e);
        }
    }

    /**
     * Invia a {@code process-index} un JSON già serializzato (es. da {@link #buildProcessIndexJsonPayload}), evitando
     * una seconda serializzazione.
     */
    public CloudIndexResult submitProcessIndexWithJson(String jsonBody) throws AiOrchestrationException {
        return submitProcessIndexWithJson(jsonBody, null);
    }

    /**
     * Come {@link #submitProcessIndexWithJson(String)}; se {@code extractionDebugTimestamp} non è vuoto e la
     * persistenza debug è attiva, salva la risposta in {@code ai_debug/debug_supabase_response_[timestamp].json}.
     */
    public CloudIndexResult submitProcessIndexWithJson(String jsonBody, String extractionDebugTimestamp)
            throws AiOrchestrationException {
        if (processIndexUrl.isEmpty()) {
            throw new AiOrchestrationException("Cloud process-index URL is empty.", null);
        }
        if (anonKey.isEmpty()) {
            throw new AiOrchestrationException("Supabase anon key is empty.", null);
        }
        if (jsonBody == null || jsonBody.isBlank()) {
            throw new AiOrchestrationException("Cloud request JSON is empty.", null);
        }
        String body = httpPostJson(processIndexUrl, jsonBody);
        AiExtractionDebugRecorder.tryWriteSupabaseResponseDebug(extractionDebugTimestamp, body);
        return parseProcessIndexResponse(body);
    }

    public CloudIndexResult submitProcessIndex(
            int startPage, int endPage, String indexPlainText, byte[] indexPngBytes, String model)
            throws AiOrchestrationException {
        return submitProcessIndexWithJson(
                buildProcessIndexJsonPayload(startPage, endPage, indexPlainText, indexPngBytes, model, null), null);
    }

    /**
     * GET check-payment: stato pagamento e, se {@code paid}, elenco segnalibri restituito dal backend (stesso
     * contratto di pdfbookmarks-backend).
     */
    public CheckPaymentResult checkPayment(String taskId) throws AiOrchestrationException {
        if (checkPaymentUrl.isEmpty()) {
            throw new AiOrchestrationException("Check-payment URL is not configured in Options.", null);
        }
        String url = appendQueryTaskId(checkPaymentUrl, taskId);
        String body = httpGet(url);
        JsonNode node = readTree(body);
        if (node == null || node.isNull()) {
            return new CheckPaymentResult(false, List.of(), "");
        }
        boolean paid = paidFlagFromCheckPaymentJson(node);
        String modelType = readPaidModelType(node);
        if (modelType.isEmpty()) {
            JsonNode dataForModel = unwrapData(node);
            if (dataForModel != null) {
                modelType = readPaidModelType(dataForModel);
            }
        }
        List<AiBookmark> bookmarks = List.of();
        if (paid) {
            bookmarks = parseBookmarksFromResponseNode(node);
            if (bookmarks.isEmpty()) {
                AiExtractionDebugRecorder.tryWriteCheckPaymentDebug(taskId, body);
            }
        }
        return new CheckPaymentResult(paid, bookmarks, paid ? modelType : "");
    }

    /**
     * Returns {@code true} if the backend reports paid / complete status for the task.
     */
    public boolean checkPaymentStatus(String taskId) throws AiOrchestrationException {
        return checkPayment(taskId).paid();
    }

    /**
     * POST {@code create-checkout}: chiede al backend un URL di pagamento per il task (contratto pdfbookmarks-backend:
     * body {@code { "taskId", "tier" }}, risposta {@code { "checkoutUrl": "..." }}).
     *
     * @param tier {@code standard} o {@code advanced} (case-insensitive)
     * @return URL da aprire nel browser (checkout provider-opaco)
     */
    public String createCheckout(String taskId, String tier) throws AiOrchestrationException {
        if (createCheckoutUrl.isEmpty()) {
            throw new AiOrchestrationException("Create-checkout URL is not configured in Options.", null);
        }
        if (anonKey.isEmpty()) {
            throw new AiOrchestrationException("Supabase anon key is empty.", null);
        }
        if (taskId == null || taskId.isBlank()) {
            throw new AiOrchestrationException("Task id is empty.", null);
        }
        String tierNorm = normalizeCheckoutTier(tier);
        if (tierNorm == null) {
            throw new AiOrchestrationException("Tier must be \"standard\" or \"advanced\".", null);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("taskId", taskId.trim());
        root.put("tier", tierNorm);
        String jsonBody;
        try {
            jsonBody = MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new AiOrchestrationException("Failed to build create-checkout JSON.", e);
        }
        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(
                    Level.INFO,
                    "create-checkout: POST {0} | body={1} | anonJwtConfigured={2}",
                    new Object[] {createCheckoutUrl, jsonBody, !anonKey.isEmpty()});
        }
        String body;
        try {
            body = httpPostJson(createCheckoutUrl, jsonBody);
        } catch (AiOrchestrationException e) {
            LOG.log(Level.WARNING, "create-checkout: HTTP error — " + e.getMessage());
            throw e;
        }
        String checkoutPageUrl = parseCreateCheckoutResponse(body);
        if (LOG.isLoggable(Level.INFO)) {
            String host = "";
            try {
                host = URI.create(checkoutPageUrl.trim()).getHost();
            } catch (Exception ignored) {
                host = "(unparsed)";
            }
            LOG.log(Level.INFO, "create-checkout: OK, redirect host={0}", host);
        }
        return checkoutPageUrl;
    }

    private static String normalizeCheckoutTier(String tier) {
        if (tier == null || tier.isBlank()) {
            return null;
        }
        String t = tier.trim().toLowerCase(Locale.ROOT);
        if ("standard".equals(t) || "advanced".equals(t)) {
            return t;
        }
        return null;
    }

    private String parseCreateCheckoutResponse(String body) throws AiOrchestrationException {
        JsonNode root = readTree(body);
        if (root == null) {
            throw new AiOrchestrationException("Empty response from create-checkout.", null);
        }
        String err = errorMessage(root);
        if (err != null && !err.isBlank()) {
            throw new AiOrchestrationException(err, null);
        }
        String url = firstText(root, "checkoutUrl", "checkout_url");
        if (url == null || url.isBlank()) {
            JsonNode data = unwrapData(root);
            if (data != null) {
                url = firstText(data, "checkoutUrl", "checkout_url");
            }
        }
        if (url == null || url.trim().isEmpty()) {
            throw new AiOrchestrationException("create-checkout response did not contain checkoutUrl.", null);
        }
        return url.trim();
    }

    /** {@code model_type} / {@code modelType} / {@code tier} dalla risposta check-payment (solo quando {@code paid}). */
    private static String readPaidModelType(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }
        String t = firstText(node, "model_type", "modelType", "tier");
        if (t == null || t.isBlank()) {
            return "";
        }
        t = t.trim().toLowerCase(Locale.ROOT);
        return "advanced".equals(t) ? "advanced" : "standard";
    }

    private static boolean paidFlagFromCheckPaymentJson(JsonNode node) {
        JsonNode paid = node.get("paid");
        if (paid != null && paid.isBoolean()) {
            return paid.asBoolean();
        }
        String ps = textOrNull(node, "payment_status", "status");
        if (ps != null) {
            String lower = ps.toLowerCase();
            return lower.contains("paid") || lower.contains("complete") || lower.contains("succeeded");
        }
        JsonNode data = node.get("data");
        if (data != null && data.isObject()) {
            return paidFlagFromCheckPaymentJson(data);
        }
        return false;
    }

    public List<AiBookmark> fetchBookmarksForTask(String taskId) throws AiOrchestrationException {
        if (fetchBookmarksUrl.isEmpty()) {
            throw new AiOrchestrationException("Fetch-bookmarks URL is not configured in Options.", null);
        }
        String url = appendQueryTaskId(fetchBookmarksUrl, taskId);
        String body = httpGet(url);
        JsonNode root = readTree(body);
        if (root != null && root.isArray()) {
            try {
                List<AiBookmark> direct =
                        MAPPER.readValue(root.traverse(), new TypeReference<List<AiBookmark>>() {});
                if (direct != null && !direct.isEmpty()) {
                    return direct;
                }
            } catch (IOException ignored) {
                // fallback: parsing tollerante elemento per elemento
            }
            return parseBookmarkArrayLenient(root);
        }
        JsonNode data = unwrapData(root);
        return parseBookmarksFromResponseNode(data != null ? data : root);
    }

    private CloudIndexResult parseProcessIndexResponse(String body) throws AiOrchestrationException {
        JsonNode root = readTree(body);
        if (root == null) {
            throw new AiOrchestrationException("Empty response from cloud service.", null);
        }
        if (root.isArray()) {
            throw new AiOrchestrationException(
                    "process-index must return a JSON object with taskId and bookmarks, not a bare array.", null);
        }
        String err = errorMessage(root);
        if (err != null && !err.isBlank()) {
            throw new AiOrchestrationException(err, null);
        }
        JsonNode data = unwrapData(root);
        JsonNode payload = data != null ? data : root;
        String taskId = readTaskId(payload);
        if (taskId.isEmpty()) {
            taskId = readTaskId(root);
        }
        List<AiBookmark> bookmarks = parseBookmarksFromResponseNode(payload);
        if (bookmarks.isEmpty()) {
            bookmarks = parseBookmarksFromResponseNode(root);
        }
        return new CloudIndexResult(taskId, bookmarks);
    }

    /**
     * Legge l'identificativo task in forme comuni: {@code taskId} (camelCase), {@code task_id}, {@code id}.
     */
    private static String readTaskId(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }
        for (String key : List.of("taskId", "task_id", "id")) {
            if (!node.has(key) || node.get(key).isNull()) {
                continue;
            }
            JsonNode v = node.get(key);
            String t = v.isTextual() ? v.asText() : v.asText();
            if (t != null) {
                t = t.trim();
                if (!t.isEmpty()) {
                    return t;
                }
            }
        }
        return "";
    }

    private static JsonNode unwrapData(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode d = root.get("data");
        if (d != null && !d.isNull()) {
            return d;
        }
        return null;
    }

    private static String errorMessage(JsonNode root) {
        String e = firstText(root, "error", "message", "detail");
        if (e != null) {
            return e;
        }
        JsonNode err = root.get("error");
        if (err != null && err.isObject()) {
            return firstText(err, "message", "code", "hint");
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String f : fieldNames) {
            JsonNode v = node.get(f);
            if (v != null && v.isTextual()) {
                String t = v.asText();
                if (t != null && !t.isBlank()) {
                    return t.trim();
                }
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String... fieldNames) {
        return firstText(node, fieldNames);
    }

    /**
     * Estrae {@code bookmarks} / {@code items} dal nodo o da {@link #unwrapData(JsonNode)} se l'array è annidato
     * (gateway che wrappa la risposta).
     */
    static List<AiBookmark> parseBookmarksFromResponseNode(JsonNode node) {
        if (node == null) {
            return List.of();
        }
        JsonNode target = nodeForBookmarkArray(node);
        if (target == null) {
            return List.of();
        }
        List<AiBookmark> strict = parseBookmarkArrayStrict(target);
        if (strict != null && !strict.isEmpty()) {
            return strict;
        }
        JsonNode arr = bookmarkArrayField(target);
        if (arr != null && arr.isArray() && !arr.isEmpty() && (strict == null || strict.isEmpty())) {
            List<AiBookmark> lenient = parseBookmarkArrayLenient(arr);
            if (!lenient.isEmpty()) {
                return lenient;
            }
        }
        return strict != null ? strict : List.of();
    }

    private static JsonNode nodeForBookmarkArray(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (bookmarkArrayField(root) != null) {
            return root;
        }
        JsonNode d = unwrapData(root);
        if (d != null && bookmarkArrayField(d) != null) {
            return d;
        }
        return root;
    }

    private static JsonNode bookmarkArrayField(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode arr = node.get("bookmarks");
        if (arr != null && arr.isArray()) {
            return arr;
        }
        arr = node.get("items");
        if (arr != null && arr.isArray()) {
            return arr;
        }
        return null;
    }

    private static List<AiBookmark> parseBookmarkArrayStrict(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode arr = node.get("bookmarks");
        if (arr == null) {
            arr = node.get("items");
        }
        if (arr == null || !arr.isArray()) {
            return null;
        }
        try {
            return MAPPER.readValue(arr.traverse(), new TypeReference<List<AiBookmark>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** Ricostruisce l'albero senza deserializzazione Jackson su tutta la lista (tollera {@code page_number} stringa/numero). */
    private static List<AiBookmark> parseBookmarkArrayLenient(JsonNode arr) {
        List<AiBookmark> out = new ArrayList<>();
        for (JsonNode el : arr) {
            AiBookmark one = bookmarkFromJsonNodeLenient(el);
            if (one != null) {
                out.add(one);
            }
        }
        return out;
    }

    private static AiBookmark bookmarkFromJsonNodeLenient(JsonNode el) {
        if (el == null || el.isNull()) {
            return null;
        }
        if (el.isObject()) {
            AiBookmark b = new AiBookmark();
            b.setTitle(readBookmarkTitle(el));
            b.setPageNumber(readBookmarkPageNumber(el));
            b.setPageLabelRaw(readBookmarkPageLabelRaw(el));
            JsonNode ch = el.get("children");
            if (ch == null || !ch.isArray()) {
                ch = el.get("items");
            }
            if (ch == null || !ch.isArray()) {
                ch = el.get("nodes");
            }
            if (ch == null || !ch.isArray()) {
                ch = el.get("bookmarks");
            }
            if (ch != null && ch.isArray()) {
                for (JsonNode c : ch) {
                    AiBookmark child = bookmarkFromJsonNodeLenient(c);
                    if (child != null) {
                        b.addChild(child);
                    }
                }
            }
            return b;
        }
        return null;
    }

    private static String readBookmarkTitle(JsonNode o) {
        JsonNode t = o.get("title");
        if (t == null) {
            t = o.get("name");
        }
        if (t == null) {
            t = o.get("label");
        }
        if (t == null) {
            t = o.get("text");
        }
        if (t == null || t.isNull()) {
            return "";
        }
        if (t.isTextual()) {
            return t.asText();
        }
        return String.valueOf(t);
    }

    private static Integer readBookmarkPageNumber(JsonNode o) {
        JsonNode p = o.get("page_number");
        if (p == null) {
            p = o.get("pageNumber");
        }
        if (p == null) {
            p = o.get("page");
        }
        if (p == null) {
            p = o.get("pg");
        }
        if (p == null || p.isNull()) {
            return null;
        }
        if (p.isInt() || p.isLong()) {
            return p.intValue();
        }
        if (p.isDouble() || p.isFloat()) {
            double d = p.asDouble();
            if (Double.isFinite(d)) {
                return (int) Math.round(d);
            }
            return null;
        }
        if (p.isTextual()) {
            String s = p.asText().trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(s.replaceFirst("^p\\.?\\s*", ""), 10);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String readBookmarkPageLabelRaw(JsonNode o) {
        JsonNode p = o.get("page_label_raw");
        if (p == null) {
            p = o.get("pageLabelRaw");
        }
        if (p == null) {
            p = o.get("page_label");
        }
        if (p == null) {
            p = o.get("pageLabel");
        }
        if (p == null || p.isNull()) {
            return "";
        }
        if (p.isTextual()) {
            return p.asText();
        }
        return String.valueOf(p);
    }

    private static JsonNode readTree(String body) throws AiOrchestrationException {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new AiOrchestrationException("Cloud response is not valid JSON.", e);
        }
    }

    private String httpPostJson(String url, String json) throws AiOrchestrationException {
        try {
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(HTTP_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + anonKey)
                            .header("apikey", anonKey)
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                LOG.log(Level.WARNING, "POST {0} -> HTTP {1}", new Object[] {url, Integer.valueOf(code)});
                throw cloudHttpError(code, resp.body());
            }
            return resp.body();
        } catch (AiOrchestrationException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "POST {0} failed: {1}", new Object[] {url, e.toString()});
            throw new AiOrchestrationException("Network error calling cloud service: " + e.getMessage(), e);
        }
    }

    private String httpGet(String url) throws AiOrchestrationException {
        try {
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(HTTP_TIMEOUT)
                            .header("Authorization", "Bearer " + anonKey)
                            .header("apikey", anonKey)
                            .GET()
                            .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw cloudHttpError(code, resp.body());
            }
            return resp.body();
        } catch (AiOrchestrationException e) {
            throw e;
        } catch (Exception e) {
            throw new AiOrchestrationException("Network error calling cloud service: " + e.getMessage(), e);
        }
    }

    /** Risposta HTML error page (CloudFront/WAF) invece di JSON Edge — evita dialog chilometrici. */
    private static boolean looksLikeCdnOrHtmlBlockPage(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String b = body.trim();
        String lower = b.toLowerCase(Locale.ROOT);
        return b.startsWith("<!")
                || lower.contains("<html")
                || lower.contains("cloudfront")
                || lower.contains("could not be satisfied")
                || lower.contains("access denied");
    }

    private static AiOrchestrationException cloudHttpError(int code, String body) {
        GatewayErrorBody parsed = parseGatewayErrorJson(body);
        boolean openAiUpstream401 =
                parsed != null
                        && parsed.innerHttpStatus == 401
                        && parsed.looksLikeOpenAiPermissionFailure();
        if (code == 401 && !openAiUpstream401) {
            String snippet = abbreviate(body);
            return new AiOrchestrationException(
                    "Cloud request failed (HTTP 401): "
                            + snippet
                            + "\n\n"
                            + Res.getString("AI_CLOUD_HTTP_401_HINT"),
                    null);
        }
        if (code == 403 && looksLikeCdnOrHtmlBlockPage(body)) {
            return new AiOrchestrationException(
                    "Cloud request failed (HTTP 403): "
                            + Res.getString("AI_CLOUD_HTTP_403_BLOCKED_BODY")
                            + "\n\n"
                            + Res.getString("AI_CLOUD_HTTP_403_HINT"),
                    null);
        }
        String headline = "Cloud request failed (HTTP " + code + ")";
        String detail =
                parsed != null && !parsed.summaryLine().isBlank()
                        ? parsed.summaryLine()
                        : abbreviate(body);
        String hint =
                openAiUpstream401
                        ? Res.getString("AI_CLOUD_HTTP_UPSTREAM_OPENAI_HINT")
                        : (code == 401 ? Res.getString("AI_CLOUD_HTTP_401_HINT") : "");
        if (hint != null && !hint.isBlank()) {
            return new AiOrchestrationException(headline + ":\n" + detail + "\n\n" + hint, null);
        }
        return new AiOrchestrationException(headline + ": " + detail, null);
    }

    /**
     * Corpo errore da Edge (es. 502) con JSON tipo {@code error}, {@code status} upstream e {@code details} stringa
     * JSON annidata (OpenAI).
     */
    private record GatewayErrorBody(String summaryLine, int innerHttpStatus) {
        boolean looksLikeOpenAiPermissionFailure() {
            if (innerHttpStatus != 401) {
                return false;
            }
            String b = summaryLine.toLowerCase(Locale.ROOT);
            return b.contains("openai")
                    || b.contains("insufficient permissions")
                    || b.contains("missing scopes")
                    || b.contains("model.request")
                    || b.contains("organization");
        }
    }

    private static GatewayErrorBody parseGatewayErrorJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            String topError = firstText(root, "error", "message");
            int innerStatus = -1;
            JsonNode st = root.get("status");
            if (st != null && st.isNumber()) {
                innerStatus = st.asInt();
            }
            String detailsRaw = "";
            JsonNode det = root.get("details");
            if (det != null && det.isTextual()) {
                detailsRaw = det.asText();
            }
            String innerMessage = "";
            if (!detailsRaw.isBlank()) {
                try {
                    JsonNode inner = MAPPER.readTree(detailsRaw);
                    if (inner != null && inner.isObject()) {
                        JsonNode errObj = inner.get("error");
                        if (errObj != null && errObj.isObject()) {
                            innerMessage = firstText(errObj, "message", "code");
                        }
                    }
                } catch (Exception ignored) {
                    innerMessage = abbreviate(detailsRaw, 500);
                }
            }
            StringBuilder line = new StringBuilder();
            if (topError != null && !topError.isBlank()) {
                line.append(topError.trim());
            }
            if (innerStatus >= 0) {
                if (line.length() > 0) {
                    line.append(" — ");
                }
                line.append("upstream HTTP ").append(innerStatus);
            }
            if (innerMessage != null && !innerMessage.isBlank()) {
                if (line.length() > 0) {
                    line.append(": ");
                }
                line.append(innerMessage.trim());
            }
            if (line.length() == 0) {
                return null;
            }
            return new GatewayErrorBody(abbreviate(line.toString(), 900), innerStatus);
        } catch (Exception e) {
            return null;
        }
    }

    private static String appendQueryTaskId(String baseUrl, String taskId) throws AiOrchestrationException {
        try {
            String enc = URLEncoder.encode(taskId, StandardCharsets.UTF_8);
            if (baseUrl.contains("?")) {
                return baseUrl + "&taskId=" + enc;
            }
            return baseUrl + "?taskId=" + enc;
        } catch (Exception e) {
            throw new AiOrchestrationException("Invalid task id.", e);
        }
    }

    private static String abbreviate(String s) {
        return abbreviate(s, 400);
    }

    private static String abbreviate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replace('\n', ' ');
        return t.length() > maxLen ? t.substring(0, maxLen) + "…" : t;
    }
}

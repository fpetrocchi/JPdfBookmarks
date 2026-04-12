package it.flavianopetrocchi.jpdfbookmarks.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * HTTP client for Supabase Edge (or compatible) endpoints: submit index images, optional payment check, fetch bookmarks.
 * <p>
 * La risposta di {@link #submitProcessIndex} è un oggetto JSON con {@code taskId} / {@code extractionId} e
 * {@code bookmarks} (array); il risultato è un {@link CloudIndexResult}.
 */
public final class SupabaseAiClient {

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
        this.processIndexUrl = Objects.requireNonNull(processIndexUrl, "processIndexUrl").trim();
        this.anonKey = normalizeSupabasePublicAnonKey(Objects.requireNonNull(anonKey, "anonKey"));
        this.checkPaymentUrl = checkPaymentUrl != null ? checkPaymentUrl.trim() : "";
        this.fetchBookmarksUrl = fetchBookmarksUrl != null ? fetchBookmarksUrl.trim() : "";
    }

    /**
     * Invia l'indice al backend Supabase. Il payload segue {@code process-index}: {@code imageBase64},
     * {@code textLayerContent} (testo layer come in {@code BookmarkExtractorAgent}), opzionale {@code model}
     * ({@code standard} / {@code advanced}), duplicato anche come {@code tier} per compatibilità con backend che
     * leggono quel nome. I parametri {@code startPage}/{@code endPage} non sono letti dall'edge
     * attuale ma restano utili in log lato client.
     */
    /**
     * Costruisce il corpo JSON inviato a {@code process-index} (stesso payload di {@link #submitProcessIndexWithJson}).
     */
    public String buildProcessIndexJsonPayload(
            int startPage, int endPage, String indexPlainText, byte[] indexPngBytes, String model)
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
        return parseProcessIndexResponse(body);
    }

    public CloudIndexResult submitProcessIndex(
            int startPage, int endPage, String indexPlainText, byte[] indexPngBytes, String model)
            throws AiOrchestrationException {
        return submitProcessIndexWithJson(
                buildProcessIndexJsonPayload(startPage, endPage, indexPlainText, indexPngBytes, model));
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
        List<AiBookmark> bookmarks = List.of();
        if (paid) {
            List<AiBookmark> parsed = parseBookmarkArray(node);
            if (parsed != null) {
                bookmarks = parsed;
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
                return direct != null ? direct : List.of();
            } catch (IOException e) {
                throw new AiOrchestrationException("Fetch response array is not a bookmark list.", e);
            }
        }
        JsonNode data = unwrapData(root);
        List<AiBookmark> list = parseBookmarkArray(data != null ? data : root);
        if (list == null) {
            list = parseBookmarkArray(root);
        }
        return list != null ? list : List.of();
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
        List<AiBookmark> bookmarks = parseBookmarkArray(payload);
        if (bookmarks == null) {
            bookmarks = parseBookmarkArray(root);
        }
        if (bookmarks == null) {
            bookmarks = List.of();
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

    private List<AiBookmark> parseBookmarkArray(JsonNode node) {
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
                throw cloudHttpError(code, resp.body());
            }
            return resp.body();
        } catch (AiOrchestrationException e) {
            throw e;
        } catch (Exception e) {
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

    private static AiOrchestrationException cloudHttpError(int code, String body) {
        String snippet = abbreviate(body);
        if (code == 401) {
            return new AiOrchestrationException(
                    "Cloud request failed (HTTP 401): "
                            + snippet
                            + "\n\n"
                            + Res.getString("AI_CLOUD_HTTP_401_HINT"),
                    null);
        }
        return new AiOrchestrationException("Cloud request failed (HTTP " + code + "): " + snippet, null);
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
        if (s == null) {
            return "";
        }
        String t = s.trim().replace('\n', ' ');
        return t.length() > 400 ? t.substring(0, 400) + "…" : t;
    }
}

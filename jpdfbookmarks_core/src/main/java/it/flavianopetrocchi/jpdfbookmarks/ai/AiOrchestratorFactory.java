/*
 * AiOrchestratorFactory.java
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

package it.flavianopetrocchi.jpdfbookmarks.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import it.flavianopetrocchi.jpdfbookmarks.AiReleasePolicy;
import it.flavianopetrocchi.jpdfbookmarks.Prefs;
import it.flavianopetrocchi.jpdfbookmarks.Res;
import java.text.MessageFormat;
import it.flavianopetrocchi.jpdfbookmarks.ai.agent.BookmarkExtractorAgent;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiOrchestrator;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.PdfVisionService;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.SupabaseAiClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Costruisce un {@link AiOrchestrator} senza Spring, usando le preferenze utente in {@link Prefs}
 * (scheda &quot;Artificial intelligence&quot; / &quot;Intelligenza artificiale&quot; nelle opzioni).
 * <p>
 * Provider {@code OPENAI}: richiede {@link Prefs#getOpenAiApiKey()}; il modello è {@link Prefs#getOpenAiModel()}
 * (fisso {@code gpt-5.4-mini}; vedi {@link Prefs#ALLOWED_OPENAI_MODEL_FOR_EXTRACTION}).
 * Rendering indice a {@link PdfVisionService#DEFAULT_RENDER_DPI} (300 DPI); timeout HTTP 60 secondi.
 * <p>
 * Provider {@code OLLAMA}: {@link Prefs#getOllamaBaseUrl()} e {@link Prefs#getOllamaModel()}.
 * Le pagine dell'indice vengono unite in un'unica immagine verticale perché modelli come {@code llama3.2-vision}
 * accettano una sola immagine per richiesta. Timeout HTTP lungo e DPI di rendering più basso (180) rispetto a OpenAI.
 * <p>
 * Modalità cloud: se {@link Prefs#isCloudIndexExtraction()} è {@code true}, si usa {@link SupabaseAiClient} con
 * {@link Prefs#getCloudProcessIndexUrl()} e {@link Prefs#getCloudSupabaseAnonKey()} (nessun modello locale).
 */
public final class AiOrchestratorFactory {

    private static final int OPENAI_HTTP_TIMEOUT_SECONDS = 60;

    /**
     * Timeout HTTP verso OpenAI: richieste vision cloud in genere completano entro questo intervallo.
     */
    private static final Duration OPENAI_HTTP_TIMEOUT = Duration.ofSeconds(OPENAI_HTTP_TIMEOUT_SECONDS);

    /**
     * Timeout HTTP verso Ollama: l'inferenza vision su immagini grandi può durare molti minuti; il default della libreria
     * (circa un minuto) provoca {@code TimeoutException} anche quando il modello sta ancora elaborando.
     */
    private static final Duration OLLAMA_HTTP_TIMEOUT = Duration.ofMinutes(45);

    /**
     * DPI per il rendering dell'indice con OpenAI: massima nitidezza per testo piccolo negli indici.
     */
    private static final float OPENAI_INDEX_RENDER_DPI = PdfVisionService.DEFAULT_RENDER_DPI;

    /**
     * DPI per il rendering dell'indice con Ollama: più basso di {@link PdfVisionService#DEFAULT_RENDER_DPI}
     * per ridurre pixel, RAM e timeout in inferenza locale.
     */
    private static final float OLLAMA_INDEX_RENDER_DPI = 180f;

    private AiOrchestratorFactory() {}

    /**
     * Equivalente a {@code createOrchestrator(new Prefs())}.
     */
    public static AiOrchestrator createOrchestrator() {
        return createOrchestrator(new Prefs());
    }

    /**
     * Crea l'orchestrator in base a {@link Prefs#getAiExtractionMode()} / cloud e, solo in dev, a
     * {@link Prefs#getAiProvider()} per locale.
     *
     * @param prefs preferenze applicative (non {@code null}); usare l'istanza della finestra principale
     *              se disponibile, così le modifiche in opzioni sono visibili senza nuovo {@code Prefs}.
     */
    public static AiOrchestrator createOrchestrator(Prefs prefs) {
        Objects.requireNonNull(prefs, "prefs");
        if (prefs.isCloudIndexExtraction()) {
            SupabaseAiClient cloud =
                    new SupabaseAiClient(
                            prefs.getCloudProcessIndexUrl().trim(),
                            prefs.getCloudSupabaseAnonKey().trim(),
                            prefs.getCloudCheckPaymentUrl(),
                            prefs.getCloudFetchFullResultsUrl(),
                            prefs.getCloudStripePricesUrl(),
                            prefs.getCloudCreateCheckoutUrl());
            PdfVisionService vision = new PdfVisionService(OPENAI_INDEX_RENDER_DPI);
            return new AiOrchestrator(vision, null, false, cloud, prefs.getCloudProcessIndexModel());
        }
        if (Prefs.AI_EXTRACTION_MODE_CLOUD.equals(prefs.getAiExtractionMode())) {
            throw new IllegalStateException(
                    AiReleasePolicy.isAdvancedAiOptionsTabEnabled()
                            ? Res.getString("AI_ERROR_CLOUD_EXTRACTION_HINT")
                            : Res.getString("AI_ERROR_CLOUD_EXTRACTION_CONSUMER"));
        }
        ChatModel chatModel = buildChatModel(prefs);
        BookmarkExtractorAgent agent =
                AiServices.builder(BookmarkExtractorAgent.class).chatModel(chatModel).build();
        boolean ollama =
                "ollama".equals(prefs.getAiProvider().trim().toLowerCase(Locale.ROOT));
        PdfVisionService vision =
                ollama
                        ? new PdfVisionService(OLLAMA_INDEX_RENDER_DPI)
                        : new PdfVisionService(OPENAI_INDEX_RENDER_DPI);
        return new AiOrchestrator(vision, agent, ollama);
    }

    private static ChatModel buildChatModel(Prefs prefs) {
        String provider = prefs.getAiProvider().trim().toLowerCase(Locale.ROOT);
        if ("openai".equals(provider)) {
            String apiKey = prefs.getOpenAiApiKey().trim();
            if (apiKey.isEmpty()) {
                throw new IllegalStateException(Res.getString("AI_ERROR_OPENAI_API_KEY"));
            }
            return OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(prefs.getOpenAiModel())
                    .timeout(OPENAI_HTTP_TIMEOUT)
                    .build();
        }
        if ("ollama".equals(provider)) {
            String baseUrl = prefs.getOllamaBaseUrl().trim();
            if (baseUrl.isEmpty()) {
                baseUrl = "http://localhost:11434";
            }
            String model = prefs.getOllamaModel().trim();
            if (model.isEmpty()) {
                model = "llama3-vision";
            }
            return OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(model)
                    .timeout(OLLAMA_HTTP_TIMEOUT)
                    .maxRetries(0)
                    .build();
        }
        throw new IllegalStateException(
                MessageFormat.format(
                        Res.getString("AI_ERROR_AI_PROVIDER_UNKNOWN"),
                        prefs.getAiProvider()));
    }
}

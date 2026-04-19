/*
 * CloudPaidBookmarksFetcher.java
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

import it.flavianopetrocchi.jpdfbookmarks.AiPreviewPaymentConfig;
import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chiamate GET verso Supabase per verificare il pagamento e scaricare i segnalibri finali, tramite {@link SupabaseAiClient}.
 */
public final class CloudPaidBookmarksFetcher {

    private final SupabaseAiClient client;

    public CloudPaidBookmarksFetcher(SupabaseAiClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Costruisce il fetcher usando URL e chiave della configurazione di anteprima/pagamento. */
    public static CloudPaidBookmarksFetcher fromAiPreviewPaymentConfig(AiPreviewPaymentConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        return new CloudPaidBookmarksFetcher(
                new SupabaseAiClient(
                        cfg.cloudProcessIndexUrl(),
                        cfg.supabaseAnonKey(),
                        cfg.cloudCheckPaymentUrl(),
                        cfg.cloudFetchFullResultsUrl(),
                        cfg.cloudStripePricesUrl()));
    }

    public boolean isPaid(String taskId) throws AiOrchestrationException {
        return client.checkPaymentStatus(taskId);
    }

    /** Stato pagamento e segnalibri inclusi nella risposta {@code check-payment} quando {@code paid} è true. */
    public SupabaseAiClient.CheckPaymentResult checkPayment(String taskId) throws AiOrchestrationException {
        return client.checkPayment(taskId);
    }

    public List<AiBookmark> fetchFullBookmarks(String taskId) throws AiOrchestrationException {
        return client.fetchBookmarksForTask(taskId);
    }

    /** Vedi {@link SupabaseAiClient#tryFetchStripeCatalogPrices()}. */
    public Optional<StripeCatalogPrices.Result> tryFetchStripeCatalogPrices() {
        return client.tryFetchStripeCatalogPrices();
    }
}
